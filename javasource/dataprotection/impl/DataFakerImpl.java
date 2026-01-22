// DataFakerImpl.java
// Intent:
// - Clean guardrails instead of provider allowlist
// - Support both Datafaker-native "#{...}" expressions and call-style "provider.method(...)" reflection
// - Unify PROVIDER + EXPRESSION via routing (generationMethod becomes optional / backward compatible)

package dataprotection.impl;

import net.datafaker.Faker;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class DataFakerImpl {

    // Practical guardrails (admin-only config, but still prevents accidental explosions)
    private static final int MAX_ARGS = 10;
    private static final int MAX_OUTPUT_CHARS = 10_000;

    private DataFakerImpl() {}

    public static Object generate(
            String valueExpression,
            dataprotection.proxies.Enum_GenerationMethod generationMethod,
            String localeTag,
            Long seed
    ) {
        final String in = (valueExpression == null) ? "" : valueExpression.trim();
        if (in.isEmpty()) {
            throw new IllegalArgumentException("Input is empty.");
        }

        final Locale loc = (localeTag == null || localeTag.trim().isEmpty())
                ? Locale.getDefault()
                : Locale.forLanguageTag(localeTag.trim());

        final Faker faker = (seed == null)
                ? new Faker(loc)
                : new Faker(loc, new Random(seed));

        // Unify PROVIDER + EXPRESSION with routing:
        // - "#{...}" -> Datafaker expression engine (with guardrails)
        // - "provider.method(...)" -> reflection invocation (with guardrails)
        // - Anything else -> clear error (since dot-args format is not supported anymore)
        final Object result = routeAndGenerate(faker, in);

        // Optional safety: cap gigantic results (mostly prevents accidents)
        if (result instanceof String) {
            return capString((String) result, MAX_OUTPUT_CHARS);
        }
        return result;
    }

    private static Object routeAndGenerate(Faker faker, String in) {
        if (looksLikeDatafakerExpression(in)) {
            final ExprHead head = extractProviderMethodFromDatafakerExpression(in);
            guardSafeHead(head.provider, head.method);
            try {
                return faker.expression(in);
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid Datafaker expression: " + in, e);
            }
        }

        if (looksLikeCallStyle(in)) {
            final CallSpec call = parseCallStyle(in);
            if (call.argTokens.size() > MAX_ARGS) {
                throw new IllegalArgumentException("Too many arguments (" + call.argTokens.size() + "). Max is " + MAX_ARGS);
            }
            guardSafeHead(call.providerName, call.methodName);
            return invokeByReflection(faker, call);
        }

        // ✅ NEW: support provider.method (no args)
        if (looksLikeNoArgsDotCall(in)) {
            final CallSpec call = parseNoArgsDotCall(in);
            guardSafeHead(call.providerName, call.methodName);
            return invokeByReflection(faker, call);
        }

        throw new IllegalArgumentException(
            "Unsupported format. Use either:\n" +
            " - Datafaker native expression: #{provider.method ...}\n" +
            " - Call-style reflection:      provider.method(arg1, arg2, ...)\n" +
            " - No-args reflection:         provider.method\n" +
            "Got: " + in
        );
    }
    
    public static long deterministicSeed(String... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");

            // Domain-separate so this seed algorithm doesn’t collide with other hashes you might use.
            md.update("DataProtection|DeterministicSeed|v1".getBytes(StandardCharsets.UTF_8));

            if (parts != null) {
                for (String p : parts) {
                    byte[] bytes = (p == null ? "" : p).getBytes(StandardCharsets.UTF_8);
                    // length-prefix to avoid ambiguity: ["ab","c"] != ["a","bc"]
                    md.update(intToBytes(bytes.length));
                    md.update(bytes);
                }
            }

            byte[] digest = md.digest();

            // First 8 bytes -> long (big-endian)
            long seed = ByteBuffer.wrap(digest, 0, 8).getLong();

            // Optional: avoid negative seeds if you prefer (Random accepts negative fine)
            // seed = seed & 0x7fffffffffffffffL;

            return seed;
        } catch (Exception e) {
            throw new RuntimeException("Unable to compute deterministic seed", e);
        }
    }

    private static byte[] intToBytes(int v) {
        return ByteBuffer.allocate(4).putInt(v).array();
    }

    // ----------------------------
    // Guardrails (instead of allowlist)
    // ----------------------------

    private static void guardSafeHead(String provider, String method) {
        // Basic sanity
        if (provider == null || provider.isBlank()) throw new IllegalArgumentException("Provider is empty.");
        if (method == null || method.isBlank()) throw new IllegalArgumentException("Method is empty.");

        // Avoid surprises / “Object method” calls by name.
        // NOTE: provider is invoked on Faker, method is invoked on provider object.
        // We block common reflective / inherited method names explicitly.
        if ("getClass".equals(method) || "wait".equals(method) || "notify".equals(method) || "notifyAll".equals(method)) {
            throw new IllegalArgumentException("Method not allowed: " + method);
        }
        // Avoid people trying to navigate Java packages (even if it won’t resolve, keep it clean)
        if (provider.contains("/") || provider.contains("\\") || provider.contains("..")) {
            throw new IllegalArgumentException("Invalid provider name: " + provider);
        }
        if (method.contains("/") || method.contains("\\") || method.contains("..")) {
            throw new IllegalArgumentException("Invalid method name: " + method);
        }
    }

    private static boolean isSafeReturnType(Class<?> rt) {
        if (rt == void.class || rt == Void.class) return false;
        if (rt.isPrimitive()) return true;
        if (rt == String.class) return true;
        if (Number.class.isAssignableFrom(rt)) return true;
        if (rt == Boolean.class || rt == Character.class) return true;
        if (Date.class.isAssignableFrom(rt)) return true;
        if (rt.isEnum()) return true;
        // Allow Object for some providers like options.option(...) that return generic Object.
        if (rt == Object.class) return true;
        return false;
    }

    private static boolean isCoercibleParamType(Class<?> p) {
        if (p.isPrimitive()) return true;
        if (p == String.class) return true;
        if (Number.class.isAssignableFrom(p)) return true;
        if (p == Boolean.class || p == Character.class) return true;
        if (p.isEnum()) return true;
        if (p == Object.class) return true; // treat token as String
        return false;
    }

    // ----------------------------
    // Reflection path: provider.method(...)
    // ----------------------------

    private static Object invokeByReflection(Faker faker, CallSpec call) {
        try {
            final Object providerObj = faker.getClass().getMethod(call.providerName).invoke(faker);

            final Method m = resolveBestMethod(providerObj.getClass(), call.methodName, call.argTokens);
            final Object[] coerced = coerceArgs(m.getParameterTypes(), m.isVarArgs(), call.argTokens);

            return m.invoke(providerObj, coerced);

        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("Unknown provider or method: " + call.providerName + "." + call.methodName, e);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Error invoking: " + call.providerName + "." + call.methodName, e);
        }
    }

    private static Method resolveBestMethod(Class<?> providerClass, String methodName, List<String> argTokens) {
        Method[] methods = providerClass.getMethods();
        List<Method> candidates = new ArrayList<>();

        for (Method m : methods) {
            if (!m.getName().equals(methodName)) continue;

            // Block Object methods (in case they match name somehow)
            if (m.getDeclaringClass() == Object.class) continue;

            // Guardrails: only allow “reasonable” contracts
            if (!isSafeReturnType(m.getReturnType())) continue;

            Class<?>[] pts = m.getParameterTypes();
            boolean varArgs = m.isVarArgs();

            // Safe parameter types
            if (!areSafeParamTypes(pts, varArgs)) continue;

            // Arity compatibility
            int pc = pts.length;
            if (varArgs) {
                int fixed = pc - 1;
                if (argTokens.size() >= fixed) candidates.add(m);
            } else {
                if (argTokens.size() == pc) candidates.add(m);
            }
        }

        if (candidates.isEmpty()) {
            throw new IllegalArgumentException(
                    "No safe overload found for " + providerClass.getSimpleName() + "." + methodName +
                    " with " + argTokens.size() + " args"
            );
        }

        // Pick first candidate for which coercion succeeds
        for (Method m : candidates) {
            try {
                coerceArgs(m.getParameterTypes(), m.isVarArgs(), argTokens);
                return m;
            } catch (IllegalArgumentException ignored) {
                // try next
            }
        }

        throw new IllegalArgumentException(
                "No overload accepts argument values for " + providerClass.getSimpleName() + "." + methodName +
                " args=" + argTokens
        );
    }

    private static boolean areSafeParamTypes(Class<?>[] pts, boolean varArgs) {
        for (int i = 0; i < pts.length; i++) {
            Class<?> p = pts[i];

            if (varArgs && i == pts.length - 1) {
                if (!p.isArray()) return false;
                p = p.getComponentType();
            }

            if (!isCoercibleParamType(p)) return false;
        }
        return true;
    }

    private static Object[] coerceArgs(Class<?>[] paramTypes, boolean varArgs, List<String> tokens) {
        if (!varArgs) {
            if (tokens.size() != paramTypes.length) throw new IllegalArgumentException("Arity mismatch.");
            Object[] out = new Object[paramTypes.length];
            for (int i = 0; i < paramTypes.length; i++) {
                out[i] = coerceOne(paramTypes[i], tokens.get(i));
            }
            return out;
        }

        // Varargs: last param is an array type
        int fixed = paramTypes.length - 1;
        if (tokens.size() < fixed) throw new IllegalArgumentException("Arity mismatch (varargs).");

        Object[] out = new Object[paramTypes.length];
        for (int i = 0; i < fixed; i++) {
            out[i] = coerceOne(paramTypes[i], tokens.get(i));
        }

        Class<?> comp = paramTypes[fixed].getComponentType();
        int varCount = tokens.size() - fixed;
        Object varArray = java.lang.reflect.Array.newInstance(comp, varCount);
        for (int i = 0; i < varCount; i++) {
            Object v = coerceOne(comp, tokens.get(fixed + i));
            java.lang.reflect.Array.set(varArray, i, v);
        }
        out[fixed] = varArray;
        return out;
    }

    private static Object coerceOne(Class<?> target, String token) {
        final String t = token == null ? "" : token.trim();
        if (t.isEmpty()) throw new IllegalArgumentException("Empty argument.");

        // If param expects String-like: pass as-is (quotes are stripped by parser)
        if (target == String.class || target == CharSequence.class || target == Object.class) return t;

        // Boolean
        if (target == boolean.class || target == Boolean.class) {
            if (!t.equalsIgnoreCase("true") && !t.equalsIgnoreCase("false")) {
                throw new IllegalArgumentException("Expected boolean, got: " + t);
            }
            return Boolean.parseBoolean(t);
        }

        // Character
        if (target == char.class || target == Character.class) {
            if (t.length() != 1) throw new IllegalArgumentException("Expected single character, got: " + t);
            return t.charAt(0);
        }

        // Numbers
        if (target == int.class || target == Integer.class) return Integer.parseInt(t);
        if (target == long.class || target == Long.class) return Long.parseLong(t);
        if (target == double.class || target == Double.class) return Double.parseDouble(t);
        if (target == float.class || target == Float.class) return Float.parseFloat(t);
        if (target == short.class || target == Short.class) return Short.parseShort(t);
        if (target == byte.class || target == Byte.class) return Byte.parseByte(t);

        // Enums
        if (target.isEnum()) {
            @SuppressWarnings({"unchecked","rawtypes"})
            Class<? extends Enum> e = (Class<? extends Enum>) target;
            return Enum.valueOf(e, t);
        }

        // Last resort: (String) constructor
        try {
            return target.getConstructor(String.class).newInstance(t);
        } catch (Exception ignored) { }

        throw new IllegalArgumentException("Unsupported param type: " + target.getName() + " for token: " + t);
    }

    // ----------------------------
    // Parsing: call-style "provider.method(arg1, ...)"
    // ----------------------------

    private static boolean looksLikeCallStyle(String s) {
        String t = s.trim();
        int open = t.indexOf('(');
        return open > 0 && t.endsWith(")") && t.substring(0, open).contains(".");
    }

    private static CallSpec parseCallStyle(String s) {
        String t = s.trim();
        int open = t.indexOf('(');
        if (open <= 0 || !t.endsWith(")")) {
            throw new IllegalArgumentException("Invalid call-style format: " + s);
        }

        String head = t.substring(0, open).trim();                  // provider.method
        String inside = t.substring(open + 1, t.length() - 1).trim(); // args

        String[] hm = head.split("\\.");
        if (hm.length != 2) {
            throw new IllegalArgumentException("Expected '<provider>.<method>(...)'. Got: " + s);
        }

        String provider = hm[0].trim();
        String method = hm[1].trim();
        if (provider.isEmpty() || method.isEmpty()) {
            throw new IllegalArgumentException("Invalid call-style head: " + head);
        }

        List<String> args = splitArgsCsvLike(inside);
        return new CallSpec(provider, method, args);
    }

    /**
     * Splits arguments like:
     *   1, 99, "x,y", 'z', true, "with \\\"escape\\\""
     *
     * - Commas separate args unless inside quotes.
     * - Both single and double quotes supported.
     * - Surrounding quotes are stripped.
     * - Basic backslash escaping within quotes is supported.
     */
    private static List<String> splitArgsCsvLike(String inside) {
        List<String> out = new ArrayList<>();
        if (inside.isEmpty()) return out;

        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        char quote = 0;

        for (int i = 0; i < inside.length(); i++) {
            char c = inside.charAt(i);

            if (inQuotes) {
                if (c == quote) {
                    inQuotes = false;
                } else if (c == '\\' && i + 1 < inside.length()) {
                    // basic escaping inside quotes
                    cur.append(inside.charAt(++i));
                } else {
                    cur.append(c);
                }
            } else {
                if (c == '"' || c == '\'') {
                    inQuotes = true;
                    quote = c;
                } else if (c == ',') {
                    out.add(cur.toString().trim());
                    cur.setLength(0);
                } else {
                    cur.append(c);
                }
            }
        }

        if (inQuotes) {
            throw new IllegalArgumentException("Unclosed quote in args: (" + inside + ")");
        }

        out.add(cur.toString().trim());

        // Validate: no empty args (e.g. "a,,b")
        for (String a : out) {
            if (a.isEmpty()) throw new IllegalArgumentException("Empty argument in: (" + inside + ")");
        }
        return out;
    }

    // ----------------------------
    // Datafaker-native expression "#{...}" handling
    // ----------------------------

    private static boolean looksLikeDatafakerExpression(String s) {
        String t = s.trim();
        return t.startsWith("#{") && t.endsWith("}");
    }

    private static ExprHead extractProviderMethodFromDatafakerExpression(String expr) {
        // Very lightweight extraction:
        // - take inside "#{...}"
        // - first token until whitespace
        // - must be provider.method
        String t = expr.trim();
        String inner = t.substring(2, t.length() - 1).trim(); // inside #{...}

        int ws = indexOfWhitespace(inner);
        String head = (ws >= 0) ? inner.substring(0, ws) : inner;

        int dot = head.indexOf('.');
        if (dot <= 0 || dot >= head.length() - 1) {
            throw new IllegalArgumentException("Expression must start with provider.method: " + expr);
        }

        String provider = head.substring(0, dot).trim();
        String method = head.substring(dot + 1).trim();
        if (provider.isEmpty() || method.isEmpty()) {
            throw new IllegalArgumentException("Expression must start with provider.method: " + expr);
        }
        return new ExprHead(provider, method);
    }

    private static int indexOfWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) return i;
        }
        return -1;
    }

    // ----------------------------
    // Standard expression without args
    // ----------------------------

    private static boolean looksLikeNoArgsDotCall(String s) {
        String t = s.trim();
        // exactly one dot, no parentheses
        if (t.contains("(") || t.contains(")")) return false;
        int firstDot = t.indexOf('.');
        if (firstDot <= 0) return false;
        return firstDot == t.lastIndexOf('.') && firstDot < t.length() - 1;
    }

    private static CallSpec parseNoArgsDotCall(String s) {
        String t = s.trim();
        String[] parts = t.split("\\.");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Expected '<provider>.<method>' with no args. Got: " + s);
        }
        String provider = parts[0].trim();
        String method = parts[1].trim();
        if (provider.isEmpty() || method.isEmpty()) {
            throw new IllegalArgumentException("Invalid '<provider>.<method>' call: " + s);
        }
        return new CallSpec(provider, method, List.of());
    }


    // ----------------------------
    // Utilities
    // ----------------------------

    private static String capString(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    public static String buildXPath(String entityName, String constraint) {
		final String base = "//" + entityName;
		final String c = (constraint == null) ? "" : constraint.trim();
		if (c.isEmpty()) return base;
		return c.startsWith("[") ? (base + c) : (base + "[" + c + "]");
	}

    // ----------------------------
    // Types
    // ----------------------------

    private static final class CallSpec {
        final String providerName;
        final String methodName;
        final List<String> argTokens;

        CallSpec(String providerName, String methodName, List<String> argTokens) {
            this.providerName = providerName;
            this.methodName = methodName;
            this.argTokens = (argTokens == null) ? List.of() : argTokens;
        }
    }

    private static final class ExprHead {
        final String provider;
        final String method;

        ExprHead(String provider, String method) {
            this.provider = provider;
            this.method = method;
        }
    }

    /*public static String deterministicInputFromValue(
            IContext ctx,
            IMendixObjectMember<?> member
    ) {
        final Object value = member.getValue(ctx);
        if (value == null) {
            throw new IllegalArgumentException(
                "Deterministic rule requires non-null value for attribute " + member.getName()
            );
        }
        return member.getName() + "=" + value.toString();
    }

    private static boolean isEmptyOriginalValue(IContext ctx, IMendixObjectMember<?> member) {
        final Object v = member.getValue(ctx);
        if (v == null) return true;
        if (v instanceof String) return ((String) v).trim().isEmpty();
        return false;
    }

    private static Object emptyOriginalValue(IContext ctx, IMendixObjectMember<?> member) {
        final Object v = member.getValue(ctx);
        if (v == null) return null;
        // preserve empty string as empty string
        if (v instanceof String) return ((String) v);
        // non-string can't really be "empty", only null
        return null;
    }
    
    private static String safeToString(Object o) {
        return o == null ? "" : String.valueOf(o);
    }    
    
    */
}

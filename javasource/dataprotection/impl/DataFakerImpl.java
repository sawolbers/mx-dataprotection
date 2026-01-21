package dataprotection.impl;

import com.mendix.systemwideinterfaces.core.IContext;
import com.mendix.systemwideinterfaces.core.IMendixObjectMember;

public final class DataFakerImpl {

    private DataFakerImpl() {}

    public static String generate(
            String valueExpression,
            dataprotection.proxies.Enum_GenerationMethod generationMethod,
            String localeTag,
            Long seed
    ) {
        final String in = (valueExpression == null) ? "" : valueExpression.trim();
        if (in.isEmpty()) {
            throw new IllegalArgumentException("Input is empty.");
        }
        if (generationMethod == null) {
            throw new IllegalArgumentException("Mode is null.");
        }

        final java.util.Locale loc = (localeTag == null || localeTag.trim().isEmpty())
                ? java.util.Locale.getDefault()
                : java.util.Locale.forLanguageTag(localeTag.trim());

        final net.datafaker.Faker faker = (seed == null)
                ? new net.datafaker.Faker(loc)
                : new net.datafaker.Faker(loc, new java.util.Random(seed));

        switch (generationMethod) {
            case PROVIDER:
                return generateByProviderPathReflection(faker, in);

            case EXPRESSION:
                return safeToString(faker.expression(in));

            default:
                throw new IllegalArgumentException("Unsupported Mode: " + generationMethod);
        }
    }

    private static String safeToString(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /**
     * "<provider>.<method>" => faker.<provider>().<method>()
     * - exactly 2 segments
     * - no args
     * - provider allow-list
     */
    
    private static String generateByProviderPathReflection(net.datafaker.Faker faker, String providerPath) {
        final String[] parts = providerPath.split("\\.");
        if (parts.length < 2) {
            throw new IllegalArgumentException(
                "ProviderPath must be '<provider>.<method>[.<intArg1>[.<intArg2>...]]', e.g. 'internet.emailAddress' or 'number.numberBetween.1.99'. Got: " + providerPath
            );
        }

        final String providerName = parts[0].trim();
        final String methodName = parts[1].trim();

        if (providerName.isEmpty() || methodName.isEmpty()) {
            throw new IllegalArgumentException("Invalid ProviderPath: " + providerPath);
        }
        if (!isAllowedProvider(providerName)) {
            throw new IllegalArgumentException("Provider not allowed: " + providerName);
        }

        // Parse int args (if any)
        final int argCount = parts.length - 2;
        final Object[] args = new Object[argCount];
        final Class<?>[] paramTypes = new Class<?>[argCount];

        for (int i = 0; i < argCount; i++) {
            final String token = parts[i + 2].trim();
            if (token.isEmpty()) {
                throw new IllegalArgumentException("Empty argument in ProviderPath: " + providerPath);
            }
            try {
                args[i] = Integer.parseInt(token);
                paramTypes[i] = int.class;
            } catch (NumberFormatException nfe) {
                throw new IllegalArgumentException("Argument must be int. Got '" + token + "' in ProviderPath: " + providerPath, nfe);
            }
        }

        try {
            final Object provider = faker.getClass().getMethod(providerName).invoke(faker);
            final Object value = provider.getClass().getMethod(methodName, paramTypes).invoke(provider, args);
            return safeToString(value);

        } catch (NoSuchMethodException e) {
            throw new IllegalArgumentException("Unknown provider or method for ProviderPath: " + providerPath, e);
        } catch (Exception e) {
            throw new RuntimeException("Error invoking ProviderPath: " + providerPath, e);
        }
    }

    private static boolean isAllowedProvider(String providerName) {
        switch (providerName) {
            case "name":
            case "internet":
            case "address":
            case "phoneNumber":
            case "company":
            case "lorem":
            case "number":
            case "date":
                return true;
            default:
                return false;
        }
    }

    public static String deterministicInputFromValue(
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
}

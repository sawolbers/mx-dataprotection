package dataprotection.impl;

import com.mendix.core.Core;
import com.mendix.systemwideinterfaces.core.IMendixObject;
import com.mendix.systemwideinterfaces.core.meta.IMetaPrimitive;

import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.Date;

public final class MendixValueCoercer {

    private MendixValueCoercer() {}

    public static Object coerceToMemberType(IMendixObject obj, String memberName, Object newValue) {
        if (newValue == null) return null;

        final IMetaPrimitive metaPrim =
                Core.getMetaObject(obj.getType()).getMetaPrimitive(memberName);

        if (metaPrim == null) {
            throw new IllegalArgumentException("Unknown attribute '" + memberName + "' on entity " + obj.getType());
        }

        final IMetaPrimitive.PrimitiveType t = metaPrim.getType();

        switch (t) {
            case String:
            case HashString:
                return coerceToString(newValue);

            case Boolean:
                return coerceToBoolean(newValue);

            case DateTime:
                return coerceToDate(newValue);

            case Integer:
            case Long:
            case AutoNumber:
                return coerceToLong(newValue);

            case Decimal:
                return coerceToBigDecimal(newValue);

            case Enum:
                return coerceToEnumKey(newValue);

            default:
                throw new IllegalArgumentException(
                        "Unsupported primitive type " + t + " for " + obj.getType() + "." + memberName
                );
        }
    }

    private static String coerceToString(Object v) {
        return (v instanceof String s) ? s : String.valueOf(v);
    }

    private static Boolean coerceToBoolean(Object v) {
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        if (v instanceof CharSequence cs) {
            String s = cs.toString().trim().toLowerCase();
            if (s.equals("true") || s.equals("1") || s.equals("yes") || s.equals("y")) return true;
            if (s.equals("false") || s.equals("0") || s.equals("no") || s.equals("n")) return false;
        }
        throw new IllegalArgumentException("Cannot coerce to Boolean: " + v);
    }

    private static Long coerceToLong(Object v) {
        if (v instanceof Number n) return n.longValue();
        if (v instanceof Boolean b) return b ? 1L : 0L;
        if (v instanceof CharSequence cs) {
            String s = cs.toString().trim();
            if (s.matches("^-?\\d+$")) return Long.parseLong(s);
        }
        throw new IllegalArgumentException("Cannot coerce to Long: " + v);
    }

    private static BigDecimal coerceToBigDecimal(Object v) {
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        if (v instanceof CharSequence cs) return new BigDecimal(cs.toString().trim());
        throw new IllegalArgumentException("Cannot coerce to BigDecimal: " + v);
    }

    private static Date coerceToDate(Object v) {
        if (v instanceof Date d) return d;

        if (v instanceof Instant i) return Date.from(i);
        if (v instanceof LocalDate ld) return Date.from(ld.atStartOfDay(ZoneId.systemDefault()).toInstant());
        if (v instanceof LocalDateTime ldt) return Date.from(ldt.atZone(ZoneId.systemDefault()).toInstant());
        if (v instanceof ZonedDateTime zdt) return Date.from(zdt.toInstant());
        if (v instanceof OffsetDateTime odt) return Date.from(odt.toInstant());

        if (v instanceof Number n) return new Date(n.longValue()); // epoch millis

        if (v instanceof CharSequence cs) {
            String s = cs.toString().trim();
            if (s.matches("^-?\\d+$")) return new Date(Long.parseLong(s));

            String iso = s.replace(' ', 'T');

            try { return Date.from(OffsetDateTime.parse(iso).toInstant()); } catch (DateTimeParseException ignored) {}
            try { return Date.from(ZonedDateTime.parse(iso).toInstant()); } catch (DateTimeParseException ignored) {}

            try {
                LocalDateTime parsed = LocalDateTime.parse(iso);
                return Date.from(parsed.atZone(ZoneId.systemDefault()).toInstant());
            } catch (DateTimeParseException ignored) {}

            try {
                LocalDate parsed = LocalDate.parse(s);
                return Date.from(parsed.atStartOfDay(ZoneId.systemDefault()).toInstant());
            } catch (DateTimeParseException ignored) {}
        }

        throw new IllegalArgumentException("Cannot coerce to DateTime: " + v + " (" + v.getClass().getName() + ")");
    }

    private static String coerceToEnumKey(Object v) {
        if (v instanceof Enum<?> e) return e.name();
        if (v instanceof CharSequence cs) return cs.toString().trim();
        return String.valueOf(v).trim();
    }
}

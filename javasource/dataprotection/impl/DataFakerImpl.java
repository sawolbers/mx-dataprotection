package dataprotection.impl;

public final class DataFakerImpl {

    private DataFakerImpl() {}

    public static String generate(
            String input,
            dataprotection.proxies.Enum_GenerationMethod mode,
            String localeTag,
            Long seed
    ) {
        final String in = (input == null) ? "" : input.trim();
        if (in.isEmpty()) {
            throw new IllegalArgumentException("Input is empty.");
        }
        if (mode == null) {
            throw new IllegalArgumentException("Mode is null.");
        }

        final java.util.Locale loc = (localeTag == null || localeTag.trim().isEmpty())
                ? java.util.Locale.getDefault()
                : java.util.Locale.forLanguageTag(localeTag.trim());

        final net.datafaker.Faker faker = (seed == null)
                ? new net.datafaker.Faker(loc)
                : new net.datafaker.Faker(loc, new java.util.Random(seed));

        switch (mode) {
            case PROVIDER:
                return generateByProviderPathReflection(faker, in);

            case EXPRESSION:
                return safeToString(faker.expression(in));

            default:
                throw new IllegalArgumentException("Unsupported Mode: " + mode);
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
        if (parts.length != 2) {
            throw new IllegalArgumentException(
                    "ProviderPath must be '<provider>.<method>' (2 segments), e.g. 'internet.emailAddress'. Got: " + providerPath
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

        try {
            final Object provider = faker.getClass().getMethod(providerName).invoke(faker);
            final Object value = provider.getClass().getMethod(methodName).invoke(provider);
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
                return true;
            default:
                return false;
        }
    }
}

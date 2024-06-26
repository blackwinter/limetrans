package hbz.limetrans.util;

import org.apache.logging.log4j.Logger;
import org.metafacture.io.FileCompression;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

public class Helpers { // checkstyle-disable-line ClassDataAbstractionCoupling|ClassFanOutComplexity

    public static final String GROUP_PREFIX = "hbz.limetrans.";
    public static final String CLASSPATH_PREFIX = "classpath:";

    private static final long KB = 1024;
    private static final String[] DISPLAY_SIZE = new String[]{"B", "K", "M", "G", "T", "P", "E"};

    private static final Path STATUS = Paths.get("/proc/self/status");
    private static final Pattern RSS_PATTERN = Pattern.compile("\\AVmRSS:\\s+(\\d+)\\s+kB");

    private static final TransformerFactory TRANSFORMER_FACTORY = TransformerFactory.newInstance();
    private static final String INDENT_AMOUNT_KEY = "{http://xml.apache.org/xslt}indent-amount";

    private static final FileCompression COMPRESSION = FileCompression.AUTO;

    private Helpers() {
        throw new IllegalAccessError("Utility class");
    }

    public static String getProperty(final String aKey) {
        return System.getProperty(GROUP_PREFIX + aKey);
    }

    public static String getProperty(final String aKey, final String aDefaultValue) {
        return getProperty(aKey, Function.identity(), aDefaultValue);
    }

    public static boolean getProperty(final String aKey, final boolean aDefaultValue) {
        final Boolean value = getProperty(aKey, Boolean::valueOf, aDefaultValue);
        return value != null ? value.booleanValue() : aDefaultValue;
    }

    public static <T> T getProperty(final String aKey, final Function<String, T> aFunction, final T aDefaultValue) {
        final String value = getProperty(aKey);
        return value != null ? value.isEmpty() ? aDefaultValue : aFunction.apply(value) : null;
    }

    public static <T extends Enum<T>> T getEnumProperty(final String aKey, final String aDefaultPropValue, final T aDefaultEnumValue, final Consumer<String> aConsumer, final UnaryOperator<String> aOperator) {
        final String propValue = getProperty(aKey, aDefaultPropValue);

        if (propValue == null) {
            if (aConsumer != null) {
                aConsumer.accept("Missing " + aKey + " property; using default: " + aDefaultEnumValue);
            }

            return aDefaultEnumValue;
        }
        else {
            try {
                return Enum.valueOf(aDefaultEnumValue.getDeclaringClass(), aOperator != null ? aOperator.apply(propValue) : propValue);
            }
            catch (final IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid " + aKey + " property: " + propValue);
            }
        }
    }

    public static Settings loadSettings(final String aPath) throws IOException {
        return loadSettings(aPath, null);
    }

    public static Settings loadSettings(final String aPath, final Consumer<Settings.Builder> aConsumer) throws IOException {
        final Settings.Builder settingsBuilder = Settings.settingsBuilder();

        if (aPath != null) {
            try (InputStream in = new FileInputStream(aPath)) {
                settingsBuilder.load(in);
            }
        }

        if (aConsumer != null) {
            aConsumer.accept(settingsBuilder);
        }

        return settingsBuilder.build();
    }

    public static boolean loadFile(final String aPath, final boolean aRequired, final String aDescription, final Consumer<String> aConsumer, final Supplier<Integer> aSupplier, final Logger aLogger) {
        final File file = new File(aPath);

        final Long rssBefore = getRss();

        if (aRequired || file.exists()) {
            try (
                InputStream inputStream = new FileInputStream(aPath);
                InputStream decompressor = COMPRESSION.createDecompressor(inputStream, true);
                Reader reader = new InputStreamReader(decompressor);
                BufferedReader bufferedReader = new BufferedReader(reader)
            ) {
                bufferedReader.lines().forEach(aConsumer);
            }
            catch (final IOException e) {
                aLogger.error("Failed to load " + aDescription + ": " + aPath, e);
                return false;
            }
        }

        final Long rssAfter = getRss();
        final boolean fileExists = file.exists();
        aLogger.info("Loaded {}: {} [mtime={}, size={}, count={}, rss={}]", aDescription, aPath,
                fileExists ? FileTime.fromMillis(file.lastModified()) : null,
                fileExists ? byteCountToDisplaySize(file.length()) : null,
                aSupplier.get(),
                byteCountToDisplaySize(rssAfter - rssBefore, 2));

        return true;
    }

    public static String slurpFile(final String aPath) throws IOException {
        return new String(Files.readAllBytes(Paths.get(aPath)));
    }

    public static String getResourcePath(final Class<?> aClass, final String aPath) throws IOException {
        final URL url = aClass.getResource(aPath);
        if (url == null) {
            throw new FileNotFoundException("Resource not found for " + aClass.toString() + ": " + aPath);
        }
        else {
            return url.getPath();
        }
    }

    public static String getPath(final Class<?> aClass, final String aPath) throws IOException {
        if (aPath != null && aPath.startsWith(CLASSPATH_PREFIX)) {
            return getResourcePath(aClass, aPath.substring(CLASSPATH_PREFIX.length()));
        }
        else {
            return aPath;
        }
    }

    public static Long getRss() {
        try {
            for (final String line : Files.readAllLines(STATUS)) {
                final Matcher matcher = RSS_PATTERN.matcher(line);

                if (matcher.matches()) {
                    return Long.parseLong(matcher.group(1)) / KB;
                }
            }
        }
        catch (final IOException e) {
        }

        return null;
    }

    public static void updateTestFile(final String aTarget, final Supplier<String> aSupplier) throws IOException {
        if (aTarget != null && getProperty("updateTestFiles", false)) {
            final String source = aSupplier.get();
            if (source != null) {
                Files.move(Paths.get(source), Paths.get(aTarget), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    public static String prettyXml(final String aString) {
        try (Reader reader = new StringReader(aString);
                Writer writer = new StringWriter()) {
            final Transformer transformer = TRANSFORMER_FACTORY.newTransformer();

            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty(INDENT_AMOUNT_KEY, "2");
            transformer.transform(new StreamSource(reader), new StreamResult(writer));

            return writer.toString();
        }
        catch (final IOException | TransformerException e) {
            System.err.println(e);
            return aString;
        }
    }

    public static String byteCountToDisplaySize(final long aSize) {
        return byteCountToDisplaySize(aSize, 0);
    }

    private static String byteCountToDisplaySize(final long aSize, final int aExponent) {
        for (int i = 0; i < DISPLAY_SIZE.length - aExponent; ++i) {
            final double quotient = aSize / Math.pow(KB, i);
            if (quotient < KB) {
                return "%.1f%s".formatted(quotient, DISPLAY_SIZE[i + aExponent]);
            }
        }

        return "%s%s".formatted(aSize, DISPLAY_SIZE[aExponent]);
    }

}

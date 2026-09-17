package io.github.polang233.velocitytoolbox.pack.host;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.zip.ZipFile;

/** 只读取有大小限制的元数据，不展开资源文件，也不替代客户端兼容性验证。 */
public final class PackArchive {
    private static final int MAX_METADATA = 65536;

    private PackArchive() {
    }

    public static void validate(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("not a regular ZIP file: " + path);
        try (ZipFile zip = new ZipFile(path.toFile())) {
            var entry = zip.getEntry("pack.mcmeta");
            if (entry == null || entry.isDirectory())
                throw new IOException("missing pack.mcmeta at ZIP root; check for an extra parent folder");
            if (zip.stream().filter(e -> e.getName().equals("pack.mcmeta")).count() != 1)
                throw new IOException("duplicate pack.mcmeta entries");
            if (entry.getSize() > MAX_METADATA) throw new IOException("pack.mcmeta exceeds 64 KiB");
            byte[] bytes;
            try (var input = zip.getInputStream(entry)) {
                bytes = input.readNBytes(MAX_METADATA + 1);
            }
            if (bytes.length > MAX_METADATA) throw new IOException("pack.mcmeta exceeds 64 KiB");
            String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            strictJson(json);
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject() || !root.getAsJsonObject().has("pack")
                    || !root.getAsJsonObject().get("pack").isJsonObject())
                throw new IOException("pack.mcmeta requires a pack object");
            var pack = root.getAsJsonObject().getAsJsonObject("pack");
            JsonElement description = pack.get("description");
            if (description == null || !(description.isJsonObject() || description.isJsonArray()
                    || description.isJsonPrimitive() && description.getAsJsonPrimitive().isString()))
                throw new IOException("pack.mcmeta requires pack.description text or a text component");
            if (pack.has("pack_format")) {
                if (!integer(pack.get("pack_format"), 1))
                    throw new IOException("pack.pack_format must be a positive integer");
            }
            boolean range = pack.has("min_format") || pack.has("max_format");
            if (range) {
                if (!format(pack.get("min_format")) || !format(pack.get("max_format")))
                    throw new IOException("pack.min_format and pack.max_format must both be valid format versions");
                if (bound(pack.get("min_format"), false) > bound(pack.get("max_format"), true))
                    throw new IOException("pack.min_format must not be newer than pack.max_format");
            } else if (!pack.has("pack_format")) {
                throw new IOException("pack.mcmeta requires pack_format or min_format and max_format");
            }
        } catch (IOException | RuntimeException error) {
            throw new IOException("invalid resource pack " + path + ": " + error.getMessage(), error);
        }
    }

    private static void strictJson(String text) throws IOException {
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            int depth = 0;
            while (reader.peek() != JsonToken.END_DOCUMENT) {
                switch (reader.peek()) {
                    case BEGIN_OBJECT -> { reader.beginObject(); depth++; }
                    case BEGIN_ARRAY -> { reader.beginArray(); depth++; }
                    case END_OBJECT -> { reader.endObject(); depth--; }
                    case END_ARRAY -> { reader.endArray(); depth--; }
                    case NAME -> reader.nextName();
                    case STRING, NUMBER -> reader.nextString();
                    case BOOLEAN -> reader.nextBoolean();
                    case NULL -> reader.nextNull();
                    default -> throw new IOException("invalid pack.mcmeta JSON");
                }
                if (depth > 64) throw new IOException("pack.mcmeta JSON exceeds 64 nesting levels");
            }
        }
    }

    private static boolean integer(JsonElement value, int minimum) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return false;
        try { return value.getAsBigDecimal().intValueExact() >= minimum; }
        catch (ArithmeticException | NumberFormatException invalid) { return false; }
    }

    private static boolean format(JsonElement value) {
        if (integer(value, 1)) return true;
        if (value == null || !value.isJsonArray()) return false;
        var parts = value.getAsJsonArray();
        return (parts.size() == 1 || parts.size() == 2) && integer(parts.get(0), 1)
                && (parts.size() == 1 || integer(parts.get(1), 0));
    }

    private static long bound(JsonElement value, boolean upper) {
        int major = value.isJsonArray() ? value.getAsJsonArray().get(0).getAsInt() : value.getAsInt();
        int minor = value.isJsonArray() && value.getAsJsonArray().size() == 2
                ? value.getAsJsonArray().get(1).getAsInt() : upper ? Integer.MAX_VALUE : 0;
        return ((long) major << 32) | minor;
    }
}

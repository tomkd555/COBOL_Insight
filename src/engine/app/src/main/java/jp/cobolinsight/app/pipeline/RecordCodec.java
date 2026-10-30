package jp.cobolinsight.app.pipeline;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.invoke.MethodType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns a graph of the engine's own records into bytes and back, for {@link ParseCache}. A value is
 * null, a string, a boxed int, long, boolean or double, an enum constant, an Optional, a List, a
 * Set, a Map, or a record whose components are values in turn. A record is rebuilt through its
 * canonical constructor, so its own checks and copies run again; a List, Set or Map comes back with
 * the iteration order it was written in, which is the order those constructors copy from.
 *
 * <p>Only classes of {@code jp.cobolinsight} are named in the bytes or loaded back from them, so
 * the bytes cannot ask for any other class. Anything else a graph holds is refused with an
 * {@link IllegalArgumentException}, and the caller then leaves that graph out of the cache.
 */
final class RecordCodec {

    private static final String OWN_PACKAGE = "jp.cobolinsight.";

    private static final int NULL = 0;
    private static final int NEW_STRING = 1;
    private static final int STRING = 2;
    private static final int INT = 3;
    private static final int LONG = 4;
    private static final int BOOLEAN = 5;
    private static final int DOUBLE = 6;
    private static final int ENUM = 7;
    private static final int EMPTY = 8;
    private static final int OPTIONAL = 9;
    private static final int LIST = 10;
    private static final int SET = 11;
    private static final int MAP = 12;
    private static final int RECORD = 13;

    /**
     * How deep values may nest: far deeper than any model the engine builds, and shallow enough
     * that reading never runs out of stack.
     */
    static final int MAX_DEPTH = 1000;

    /** The components and the canonical constructor of each record class met so far. */
    private static final Map<Class<?>, Shape> SHAPES = new ConcurrentHashMap<>();

    private record Shape(RecordComponent[] components, Constructor<?> constructor) {
    }

    private RecordCodec() {
    }

    /** The bytes of one value. Each call has its own string table, so the bytes stand alone. */
    static byte[] encode(Object value) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            new Writer(out).write(value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /** The value {@link #encode} wrote. */
    static Object decode(byte[] encoded) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded))) {
            return new Reader(in).read();
        }
    }

    private static Shape shapeOf(Class<?> type) {
        return SHAPES.computeIfAbsent(type, key -> {
            RecordComponent[] components = key.getRecordComponents();
            Class<?>[] types = new Class<?>[components.length];
            for (int i = 0; i < components.length; i++) {
                types[i] = components[i].getType();
                components[i].getAccessor().setAccessible(true);
            }
            try {
                Constructor<?> constructor = key.getDeclaredConstructor(types);
                constructor.setAccessible(true);
                return new Shape(components, constructor);
            } catch (NoSuchMethodException e) {
                throw new IllegalArgumentException("no canonical constructor: " + key.getName(), e);
            }
        });
    }

    private static final class Writer {

        private final DataOutputStream out;
        private final Map<String, Integer> strings = new HashMap<>();

        Writer(DataOutputStream out) {
            this.out = out;
        }

        /** How deep the values being written are nested: no deeper than a reader takes back. */
        private int depth;

        void write(Object value) throws IOException {
            if (++depth > MAX_DEPTH) {
                throw new IllegalArgumentException("values nested deeper than " + MAX_DEPTH);
            }
            try {
                writeValue(value);
            } finally {
                depth--;
            }
        }

        private void writeValue(Object value) throws IOException {
            if (value == null) {
                out.writeByte(NULL);
            } else if (value instanceof String string) {
                writeString(string);
            } else if (value instanceof Integer number) {
                out.writeByte(INT);
                out.writeInt(number);
            } else if (value instanceof Long number) {
                out.writeByte(LONG);
                out.writeLong(number);
            } else if (value instanceof Boolean flag) {
                out.writeByte(BOOLEAN);
                out.writeBoolean(flag);
            } else if (value instanceof Double number) {
                out.writeByte(DOUBLE);
                out.writeDouble(number);
            } else if (value instanceof Enum<?> constant) {
                out.writeByte(ENUM);
                writeString(ownClass(constant.getDeclaringClass()).getName());
                writeString(constant.name());
            } else if (value instanceof Optional<?> optional) {
                if (optional.isEmpty()) {
                    out.writeByte(EMPTY);
                } else {
                    out.writeByte(OPTIONAL);
                    write(optional.orElseThrow());
                }
            } else if (value instanceof List<?> list) {
                out.writeByte(LIST);
                out.writeInt(list.size());
                for (Object element : list) {
                    write(element);
                }
            } else if (value instanceof Set<?> set) {
                out.writeByte(SET);
                out.writeInt(set.size());
                for (Object element : set) {
                    write(element);
                }
            } else if (value instanceof Map<?, ?> map) {
                out.writeByte(MAP);
                out.writeInt(map.size());
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    write(entry.getKey());
                    write(entry.getValue());
                }
            } else if (value instanceof Record record) {
                Shape shape = shapeOf(ownClass(record.getClass()));
                out.writeByte(RECORD);
                writeString(record.getClass().getName());
                for (RecordComponent component : shape.components()) {
                    try {
                        write(component.getAccessor().invoke(record));
                    } catch (IllegalAccessException | InvocationTargetException e) {
                        throw new IllegalArgumentException(component.toString(), e);
                    }
                }
            } else {
                throw new IllegalArgumentException("unsupported value: " + value.getClass().getName());
            }
        }

        private void writeString(String string) throws IOException {
            Integer index = strings.get(string);
            if (index != null) {
                out.writeByte(STRING);
                out.writeInt(index);
                return;
            }
            strings.put(string, strings.size());
            byte[] utf8 = string.getBytes(StandardCharsets.UTF_8);
            out.writeByte(NEW_STRING);
            out.writeInt(utf8.length);
            out.write(utf8);
        }
    }

    private static final class Reader {

        private final DataInputStream in;
        private final List<String> strings = new ArrayList<>();

        Reader(DataInputStream in) {
            this.in = in;
        }

        /** How deep the values being read are nested, which the bytes cannot take past the limit. */
        private int depth;

        Object read() throws IOException {
            if (++depth > MAX_DEPTH) {
                throw new IOException("values nested deeper than " + MAX_DEPTH);
            }
            try {
                return readValue();
            } finally {
                depth--;
            }
        }

        private Object readValue() throws IOException {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case NULL:
                    return null;
                case NEW_STRING:
                case STRING:
                    return readString(tag);
                case INT:
                    return in.readInt();
                case LONG:
                    return in.readLong();
                case BOOLEAN:
                    return in.readBoolean();
                case DOUBLE:
                    return in.readDouble();
                case ENUM:
                    return enumConstant(ownClass(readName()), readName());
                case EMPTY:
                    return Optional.empty();
                case OPTIONAL:
                    return Optional.of(read());
                case LIST: {
                    // A size read from the bytes sizes nothing ahead of the elements that back it.
                    int size = in.readInt();
                    List<Object> list = new ArrayList<>();
                    for (int i = 0; i < size; i++) {
                        list.add(read());
                    }
                    return list;
                }
                case SET: {
                    int size = in.readInt();
                    Set<Object> set = new LinkedHashSet<>();
                    for (int i = 0; i < size; i++) {
                        set.add(read());
                    }
                    return set;
                }
                case MAP: {
                    int size = in.readInt();
                    Map<Object, Object> map = new LinkedHashMap<>();
                    for (int i = 0; i < size; i++) {
                        map.put(read(), read());
                    }
                    return map;
                }
                case RECORD:
                    return readRecord();
                default:
                    throw new IOException("unknown tag " + tag);
            }
        }

        private Object readRecord() throws IOException {
            Class<?> type = ownClass(readName());
            if (!type.isRecord()) {
                throw new IOException("a record type was expected: " + type.getName());
            }
            Shape shape = shapeOf(type);
            Object[] arguments = new Object[shape.components().length];
            for (int i = 0; i < arguments.length; i++) {
                arguments[i] = read();
                if (!conforms(arguments[i], shape.components()[i].getGenericType())) {
                    throw new IOException("a value of another type for "
                            + shape.components()[i]);
                }
            }
            try {
                return shape.constructor().newInstance(arguments);
            } catch (ReflectiveOperationException | IllegalArgumentException e) {
                throw new IOException("cannot rebuild " + type.getName(), e);
            }
        }

        private String readName() throws IOException {
            return readString(in.readUnsignedByte());
        }

        private String readString(int tag) throws IOException {
            if (tag == STRING) {
                int index = in.readInt();
                if (index < 0 || index >= strings.size()) {
                    throw new IOException("no string " + index);
                }
                return strings.get(index);
            }
            if (tag != NEW_STRING) {
                throw new IOException("a string was expected at tag " + tag);
            }
            int length = in.readInt();
            if (length < 0) {
                throw new IOException("a negative length");
            }
            byte[] utf8 = in.readNBytes(length);
            if (utf8.length != length) {
                throw new EOFException();
            }
            String string = new String(utf8, StandardCharsets.UTF_8);
            strings.add(string);
            return string;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private static Object enumConstant(Class<?> type, String name) throws IOException {
            if (!type.isEnum()) {
                throw new IOException("an enum type was expected: " + type.getName());
            }
            try {
                return Enum.valueOf((Class) type, name);
            } catch (IllegalArgumentException e) {
                throw new IOException("no constant " + name + " in " + type.getName(), e);
            }
        }
    }

    /**
     * Whether a value read back fits the declared type of the component it goes into, the element
     * types of a List, Set, Map or Optional included: a record's constructor copies a list
     * without looking at its elements, and a wrong element would fail only where it is used.
     */
    private static boolean conforms(Object value, Type type) {
        if (value == null || type instanceof TypeVariable<?>) {
            return true;
        }
        if (type instanceof WildcardType wildcard) {
            return conforms(value, wildcard.getUpperBounds()[0]);
        }
        if (type instanceof Class<?> plain) {
            Class<?> boxed = plain.isPrimitive()
                    ? MethodType.methodType(plain).wrap().returnType() : plain;
            return boxed.isInstance(value);
        }
        if (!(type instanceof ParameterizedType parameterized)
                || !((Class<?>) parameterized.getRawType()).isInstance(value)) {
            return false;
        }
        Type[] arguments = parameterized.getActualTypeArguments();
        if (value instanceof Collection<?> elements) {
            return elements.stream().allMatch(element -> conforms(element, arguments[0]));
        }
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream().allMatch(entry -> conforms(entry.getKey(), arguments[0])
                    && conforms(entry.getValue(), arguments[1]));
        }
        if (value instanceof Optional<?> optional) {
            return optional.map(inner -> conforms(inner, arguments[0])).orElse(true);
        }
        return true;
    }

    /** The class itself, when it is one of the engine's own. */
    private static Class<?> ownClass(Class<?> type) {
        if (!type.getName().startsWith(OWN_PACKAGE)) {
            throw new IllegalArgumentException("a class outside the engine: " + type.getName());
        }
        return type;
    }

    private static Class<?> ownClass(String name) throws IOException {
        if (!name.startsWith(OWN_PACKAGE)) {
            throw new IOException("a class outside the engine: " + name);
        }
        try {
            return Class.forName(name, false, RecordCodec.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IOException("unknown class " + name, e);
        }
    }
}

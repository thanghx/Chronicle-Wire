/*
 * Copyright 2016 higherfrequencytrading.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.openhft.chronicle.wire;

import net.openhft.chronicle.bytes.*;
import net.openhft.chronicle.bytes.ref.BinaryIntReference;
import net.openhft.chronicle.bytes.ref.BinaryLongArrayReference;
import net.openhft.chronicle.bytes.ref.BinaryLongReference;
import net.openhft.chronicle.bytes.util.Bit8StringInterner;
import net.openhft.chronicle.bytes.util.Compression;
import net.openhft.chronicle.bytes.util.UTF8StringInterner;
import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.Maths;
import net.openhft.chronicle.core.io.IORuntimeException;
import net.openhft.chronicle.core.util.*;
import net.openhft.chronicle.core.values.IntValue;
import net.openhft.chronicle.core.values.LongArrayValues;
import net.openhft.chronicle.core.values.LongValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Externalizable;
import java.io.IOException;
import java.io.Serializable;
import java.nio.BufferUnderflowException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.function.*;

import static net.openhft.chronicle.core.util.ReadResolvable.readResolve;
import static net.openhft.chronicle.wire.BinaryWire.AnyCodeMatch.ANY_CODE_MATCH;
import static net.openhft.chronicle.wire.BinaryWireCode.*;

/**
 * This Wire is a binary translation of TextWire which is a sub set of YAML.
 */
public class BinaryWire extends AbstractWire implements Wire {
    private static final UTF8StringInterner UTF8 = new UTF8StringInterner(4096);
    private static final Bit8StringInterner BIT8 = new Bit8StringInterner(1024);
    static int SPEC = Integer.getInteger("BinaryWire.SPEC", 16);
    private final FixedBinaryValueOut fixedValueOut = new FixedBinaryValueOut();
    @NotNull
    private final FixedBinaryValueOut valueOut;
    @NotNull
    private final BinaryValueIn valueIn;
    private final boolean numericFields;
    private final boolean fieldLess;
    private final int compressedSize;
    private final WriteDocumentContext writeContext = new WriteDocumentContext(this);
    private final ReadDocumentContext readContext;
    private final StringBuilder stringBuilder = new StringBuilder();
    DefaultValueIn defaultValueIn;
    private String compression;

    public BinaryWire(@NotNull Bytes bytes) {
        this(bytes, false, false, false, Integer.MAX_VALUE, "binary", true);
    }

    public BinaryWire(@NotNull Bytes bytes, boolean fixed, boolean numericFields, boolean fieldLess, int compressedSize, String compression, boolean supportDelta) {
        super(bytes, false);
        this.numericFields = numericFields;
        this.fieldLess = fieldLess;
        this.compressedSize = compressedSize;
        valueOut = getFixedBinaryValueOut(fixed);
        this.compression = compression;
        valueIn = supportDelta ? new DeltaValueIn() : new BinaryValueIn();
        readContext = new ReadDocumentContext(this, supportDelta);
    }

    @NotNull
    public static BinaryWire binaryOnly(@NotNull Bytes bytes) {
        return new BinaryWire(bytes, false, false, false, Integer.MAX_VALUE, "binary", false);
    }

    static boolean textable(@NotNull BytesStore bytes) {
        for (long pos = bytes.readPosition(); pos < bytes.readLimit(); pos++) {
            if (bytes.readByte(pos) >= 127)
                return false;
        }
        return true;
    }

    @NotNull
    StringBuilder acquireStringBuilder() {
        stringBuilder.setLength(0);
        return stringBuilder;
    }

    @NotNull
    protected FixedBinaryValueOut getFixedBinaryValueOut(boolean fixed) {
        return fixed ? fixedValueOut : new BinaryValueOut();
    }

    @NotNull
    protected BinaryValueIn getBinaryValueIn() {
        return new DeltaValueIn();
    }

    public void clear() {
        bytes.clear();
        valueIn.resetState();
        valueOut.resetState();
    }

    public boolean fieldLess() {
        return fieldLess;
    }

    @NotNull
    @Override
    public DocumentContext writingDocument(boolean metaData) {
        writeContext.start(metaData);
        return writeContext;
    }

    @NotNull
    @Override
    public DocumentContext readingDocument() {
        readContext.start();
        return readContext;
    }

    @NotNull
    @Override
    public DocumentContext readingDocument(long readLocation) {
        @NotNull Bytes<?> bytes = bytes();
        final long readPosition = bytes.readPosition();
        final long readLimit = bytes.readLimit();
        bytes.readPositionUnlimited(readLocation);

        readContext.start();
        readContext.closeReadLimit(readLimit);
        readContext.closeReadPosition(readPosition);
        return readContext;
    }

    /**
     * Typicality used for debugging, this method does not progress the read position and should
     * only be used when inside a reading document.
     *
     * @return when readReading a document returns the current document as a YAML String, if you are
     * not currently reading a document, and empty string will be return.
     */
    public String readingPeekYaml() {
        long start = readContext.start;
        if (start == -1)
            return "";
        return Wires.fromSizePrefixedBlobs(bytes, start);
    }

    @Override
    public void copyTo(@NotNull WireOut wire) {
        while (bytes.readRemaining() > 0) {
            copyOne(wire);
        }
    }

    public void copyOne(@NotNull WireOut wire) {
        int peekCode = peekCode();
        outerSwitch:
        switch (peekCode >> 4) {
            case BinaryWireHighCode.NUM0:
            case BinaryWireHighCode.NUM1:
            case BinaryWireHighCode.NUM2:
            case BinaryWireHighCode.NUM3:
            case BinaryWireHighCode.NUM4:
            case BinaryWireHighCode.NUM5:
            case BinaryWireHighCode.NUM6:
            case BinaryWireHighCode.NUM7:
                bytes.uncheckedReadSkipOne();
                wire.getValueOut().uint8checked(peekCode);
                break;

            case BinaryWireHighCode.CONTROL:
                switch (peekCode) {
                    case PADDING:
                        bytes.uncheckedReadSkipOne();
                        break outerSwitch;
                    case PADDING32:
                        bytes.uncheckedReadSkipOne();
                        bytes.readSkip(bytes.readUnsignedInt());
                        break outerSwitch;

                    case BYTES_LENGTH8: {
                        bytes.uncheckedReadSkipOne();
                        int len = bytes.readUnsignedByte();
                        readWithLength(wire, len);
                        break outerSwitch;
                    }

                    case BYTES_LENGTH16: {
                        bytes.uncheckedReadSkipOne();
                        int len = bytes.readUnsignedShort();
                        readWithLength(wire, len);
                        break outerSwitch;
                    }

                    case BYTES_LENGTH32: {
                        bytes.uncheckedReadSkipOne();
                        int len = bytes.readInt();
                        readWithLength(wire, len);
                        break outerSwitch;
                    }

                    case U8_ARRAY:
                        bytes.uncheckedReadSkipOne();
                        if (textable(bytes))
                            wire.getValueOut().text(bytes);
                        else
                            wire.getValueOut().bytes(bytes);
                        bytes.readPositionRemaining(bytes.readLimit(), 0);
                        break outerSwitch;

                    case I64_ARRAY:
                        bytes.uncheckedReadSkipOne();
                        long len2 = bytes.readLong();
                        long used = bytes.readLong();
                        wire.getValueOut().sequence(o -> {
                            wire.writeComment("length: " + len2 + ", used: " + used);
                            for (long i = 0; i < len2; i++) {
                                long v = bytes.readLong();
                                if (i == used) {
                                    o.leaf(true);
                                }
                                o.int64(v);
                            }
                        });

                        break outerSwitch;
                    case FIELD_ANCHOR: {
                        bytes.uncheckedReadSkipOne();
                        @NotNull StringBuilder sb = acquireStringBuilder();
                        readFieldAnchor(sb);
                        wire.write(sb);
                        break outerSwitch;
                    }
                    case ANCHOR:
                    case UPDATED_ALIAS: {
                        @Nullable final Object o = valueIn.object();
                        wire.getValueOut().object(o);
                        break outerSwitch;
                    }
                }
                unknownCode(wire);
                break;

            case BinaryWireHighCode.FLOAT:
                bytes.uncheckedReadSkipOne();
                try {
                    Number d = readFloat0(peekCode);
                    wire.getValueOut().object(d);
                } catch (Exception e) {
                    unknownCode(wire);
                }
                break;

            case BinaryWireHighCode.INT:
                bytes.uncheckedReadSkipOne();
                try {
                    if (peekCode == INT64_0x) {
                        wire.getValueOut().int64_0x(bytes.readLong());
                    } else {
                        Number l = readInt0object(peekCode);
                        wire.getValueOut().object(l);
                    }
                } catch (Exception e) {
                    unknownCode(wire);
                }
                break;

            case BinaryWireHighCode.SPECIAL:
                copySpecial(wire, peekCode);
                break;

            case BinaryWireHighCode.FIELD0:
            case BinaryWireHighCode.FIELD1:
                @Nullable StringBuilder fsb = readField(peekCode, ANY_CODE_MATCH.name(), ANY_CODE_MATCH.code(), acquireStringBuilder(), false);
                wire.write(fsb);
                break;

            case BinaryWireHighCode.STR0:
            case BinaryWireHighCode.STR1:
                bytes.uncheckedReadSkipOne();
                @Nullable StringBuilder sb = readText(peekCode, acquireStringBuilder());
                wire.getValueOut().text(sb);
                break;
        }
    }

    public void readWithLength(@NotNull WireOut wire, int len) {
        long lim = bytes.readLimit();
        try {
            bytes.readLimit(bytes.readPosition() + len);
            @NotNull final ValueOut valueOut = wire.getValueOut();
            switch (getBracketTypeNext()) {
                case MAP:
                    valueOut.marshallable(this::copyTo);
                    break;
                case SEQ:
                    valueOut.sequence(v -> copyTo(v.wireOut()));
                    break;
                case NONE:
                    @Nullable Object object = this.getValueIn().object();
                    if (object instanceof BytesStore) {
                        @Nullable BytesStore bytes = (BytesStore) object;
                        if (textable(bytes)) {
                            valueOut.text(bytes);
                            break;
                        }
                    }
                    valueOut.object(object);
                    break;
            }
        } finally {
            bytes.readLimit(lim);
        }
    }

    private void unknownCode(@NotNull WireOut wire) {
        wire.writeComment("# " + stringForCode(bytes.readUnsignedByte()));
    }

    @NotNull
    private BracketType getBracketTypeNext() {
        int peekCode = peekCode();
        return getBracketTypeFor(peekCode);
    }

    @NotNull
    BracketType getBracketTypeFor(int peekCode) {
        if (peekCode >= FIELD_NAME0 && peekCode <= FIELD_NAME31)
            return BracketType.MAP;
        switch (peekCode) {
            case FIELD_NAME_ANY:
            case EVENT_NAME:
            case EVENT_OBJECT:
                return BracketType.MAP;
            case U8_ARRAY:
            case I64_ARRAY:
                return BracketType.NONE;
            default:
                return BracketType.SEQ;
        }
    }

    @NotNull
    @Override
    public ValueIn read(String fieldName) {
        read(fieldName, fieldName.hashCode(), null, Function.identity());
        return valueIn;
    }

    @NotNull
    @Override
    public ValueIn read() {
        readField(acquireStringBuilder(), null, ANY_CODE_MATCH.code());
        return valueIn;
    }

    @NotNull
    @Override
    public ValueIn read(@NotNull WireKey key) {
        return read(key.name(), key.code(), key, WireKey::defaultValue);
    }

    private <T> ValueIn read(CharSequence keyName, int keyCode, T defaultSource, Function<T, Object> defaultLookup) {
        ValueInState curr = valueIn.curr();
        @NotNull StringBuilder sb = acquireStringBuilder();
        // did we save the position last time
        // so we could go back and parseOne an older field?
        if (curr.savedPosition() > 0) {
            bytes.readPosition(curr.savedPosition() - 1);
            curr.savedPosition(0L);
        }
        while (bytes.readRemaining() > 0) {
            long position = bytes.readPosition();
            // at the current position look for the field.
            readField(sb, keyName, keyCode);
            if (sb.length() == 0 || StringUtils.isEqual(sb, keyName))
                return valueIn;

            // if no old field nor current field matches, set to default values.
            // we may come back and set the field later if we find it.
            curr.addUnexpected(position);
            valueIn.consumeNext();
            consumePadding();
        }

        return read2(keyName, keyCode, defaultSource, defaultLookup, curr, sb, keyName);
    }

    protected <T> ValueIn read2(CharSequence keyName,
                                int keyCode,
                                T defaultSource,
                                Function<T, Object> defaultLookup,
                                @NotNull ValueInState curr,
                                @NotNull StringBuilder sb,
                                CharSequence name) {
        long position2 = bytes.readLimit();

        // if not a match go back and look at old fields.
        for (int i = 0; i < curr.unexpectedSize(); i++) {
            bytes.readPosition(curr.unexpected(i));
            readField(sb, keyName, keyCode);
            if (sb.length() == 0 || StringUtils.isEqual(sb, name)) {
                // if an old field matches, remove it, save the current position
                curr.removeUnexpected(i);
                curr.savedPosition(position2 + 1);
                return valueIn;
            }
        }
        bytes.readPosition(position2);

        if (defaultValueIn == null)
            defaultValueIn = new DefaultValueIn(this);
        defaultValueIn.defaultValue = defaultLookup.apply(defaultSource);
        return defaultValueIn;
    }

    @NotNull
    @Override
    public ValueIn readEventName(@NotNull StringBuilder name) {
        readField(name, null, ANY_CODE_MATCH.code());
        return valueIn;
    }

    @NotNull
    @Override
    public ValueIn read(@NotNull StringBuilder name) {
        readField(name, null, ANY_CODE_MATCH.code());
        return valueIn;
    }

    @NotNull
    @Override
    public ValueIn getValueIn() {
        return valueIn;
    }

    @NotNull
    @Override
    public Wire readComment(@NotNull StringBuilder s) {
        if (peekCode() == COMMENT) {
            bytes.uncheckedReadSkipOne();
            bytes.readUtf8(s);
        } else {
            s.setLength(0);
        }
        return this;
    }

    @Nullable
    private StringBuilder readField(@NotNull StringBuilder name, CharSequence keyName, int keyCode) {
        int peekCode = peekCodeAfterPadding();
        return readField(peekCode, keyName, keyCode, name, true);
    }

    private int peekCodeAfterPadding() {
        int peekCode = peekCode();
        if (peekCode == PADDING || peekCode == PADDING32 || peekCode == COMMENT) {
            consumePadding(false);
            peekCode = peekCode();
        }
        return peekCode;
    }

    @Nullable
    @Override
    public <K> K readEvent(@NotNull Class<K> expectedClass) {
        int peekCode = peekCodeAfterPadding();
        switch (peekCode >> 4) {
            case BinaryWireHighCode.END_OF_STREAM:
                return null;

            case BinaryWireHighCode.CONTROL:
            case BinaryWireHighCode.SPECIAL:
                return readSpecialField(peekCode, expectedClass);

            case BinaryWireHighCode.FIELD0:
            case BinaryWireHighCode.FIELD1:
                return readSmallField(peekCode, expectedClass);

            default:
                return null;
        }
    }

    @NotNull
    private <K> K readSmallField(int peekCode, Class<K> expectedClass) {
        bytes.uncheckedReadSkipOne();
        final int length = peekCode & 0x1F;
        final String s = BIT8.intern(bytes, length);
        bytes.readSkip(length);
        return ObjectUtils.convertTo(expectedClass, s);
    }

    @Nullable
    private <K> K readSpecialField(int peekCode, @NotNull Class<K> expectedClass) {
        switch (peekCode) {
            case FIELD_NUMBER:
                bytes.uncheckedReadSkipOne();
                long fieldId = bytes.readStopBit();
                return ObjectUtils.convertTo(expectedClass, fieldId);

            case FIELD_NAME_ANY:
            case EVENT_NAME:
                bytes.uncheckedReadSkipOne();
                @Nullable StringBuilder sb = read8bit();
                return ObjectUtils.convertTo(expectedClass, WireInternal.INTERNER.intern(sb));

            case FIELD_ANCHOR:
                bytes.uncheckedReadSkipOne();
                throw new UnsupportedOperationException();

            case EVENT_OBJECT:
                bytes.uncheckedReadSkipOne();
                return valueIn.object(expectedClass);
        }

        return null;
    }

    @Nullable
    StringBuilder read8bit() {
        @NotNull StringBuilder sb = acquireStringBuilder();
        return bytes.read8bit(sb) ? sb : null;
    }

    public void consumePadding() {
        consumePadding(false);
    }

    void consumePadding(boolean consumeType) {
        while (true) {
            int code = peekCode();
            switch (code) {
                case PADDING:
                    bytes.uncheckedReadSkipOne();
                    break;

                case PADDING32:
                    bytes.uncheckedReadSkipOne();
                    bytes.readSkip(bytes.readUnsignedInt());
                    break;

                case TYPE_PREFIX:
                    if (!consumeType)
                        return;
                    // fall through

                case COMMENT: {
                    bytes.uncheckedReadSkipOne();
                    readUtf8();
                    break;
                }

                default:
                    return;
            }
        }
    }

    protected int peekCode() {
        return bytes.peekUnsignedByte();
    }

    private StringBuilder readField(int peekCode, CharSequence keyName, int keyCode, @NotNull StringBuilder sb, boolean missingOk) {
        sb.setLength(0);
        switch (peekCode >> 4) {
            case BinaryWireHighCode.END_OF_STREAM:
                break;

            case BinaryWireHighCode.CONTROL:
            case BinaryWireHighCode.SPECIAL:
                return readSpecialField(peekCode, keyName, keyCode, sb);

            case BinaryWireHighCode.FIELD0:
            case BinaryWireHighCode.FIELD1:
                return readSmallField(peekCode, sb);
            default:
                if (missingOk)
                    // if it's not a field, perhaps none was written.
                    break;
                throw new UnsupportedOperationException("Unknown code " + stringForCode(peekCode));
        }
        // if field-less accept anything in order.
        if (fieldLess) {
            return sb;
        }

        return null;
    }

    @NotNull
    private StringBuilder readSmallField(int peekCode, @NotNull StringBuilder sb) {
        bytes.uncheckedReadSkipOne();
        if (bytes.isDirectMemory()) {
            AppendableUtil.parse8bit_SB1(bytes, sb, peekCode & 0x1f);
        } else {
            AppendableUtil.parse8bit(bytes, sb, peekCode & 0x1f);
        }
        return sb;
    }

    @Nullable
    private StringBuilder readSpecialField(int peekCode, CharSequence keyName, int keyCode, @NotNull StringBuilder sb) {
        switch (peekCode) {
            case FIELD_NUMBER:
                bytes.uncheckedReadSkipOne();
                long fieldId = bytes.readStopBit();
                return readFieldNumber(keyName, keyCode, sb, fieldId);
            case FIELD_NAME_ANY:
            case EVENT_NAME:
                bytes.uncheckedReadSkipOne();
                bytes.read8bit(sb);
                return sb;

            case FIELD_ANCHOR:
                bytes.uncheckedReadSkipOne();
                return readFieldAnchor(sb);

            case EVENT_OBJECT:
                valueIn.text(sb);
                return sb;
        }

        return null;
    }

    @NotNull
    protected StringBuilder readFieldAnchor(@NotNull StringBuilder sb) {
        if (valueIn instanceof DeltaValueIn) {
            @NotNull DeltaValueIn in = (DeltaValueIn) valueIn;

            int ref = Maths.toUInt31(bytes.readStopBit());
            if (ref >= in.inField.length)
                in.inField = Arrays.copyOf(in.inField, in.inField.length * 2);
            bytes.readUtf8(sb);
            in.inField[ref] = sb.toString();
            return sb;
        } else {
            throw new UnsupportedOperationException();
        }
    }

    @NotNull
    protected StringBuilder readFieldNumber(CharSequence keyName, int keyCode, @NotNull StringBuilder sb, long fieldId) {
        if (valueIn instanceof DeltaValueIn) {
            @NotNull DeltaValueIn in = (DeltaValueIn) valueIn;
            if (fieldId >= 0 && fieldId < in.inField.length) {
                String s = in.inField[(int) fieldId];
                if (s != null)
                    return sb.append(s);
            }
        }

        if (keyCode == ANY_CODE_MATCH.code()) {
            sb.append(fieldId);
            return sb;
        }
        int codeMatch = keyCode;
        if (fieldId != codeMatch)
            return sb;

        sb.append(keyName);
        return sb;
    }

    @NotNull
    private <ACS extends Appendable & CharSequence> ACS getStringBuilder(int code, @NotNull ACS sb) {
        bytes.parseUtf8(sb, code & 0x1f);
        return sb;
    }

    private void copySpecial(@NotNull WireOut wire, int peekCode) {
        switch (peekCode) {
            case COMMENT: {
                bytes.uncheckedReadSkipOne();
                @Nullable StringBuilder sb = readUtf8();
                wire.writeComment(sb);
                break;
            }

            case TIME:
                wire.getValueOut().time(getValueIn().time());
                break;
            case DATE:
                wire.getValueOut().date(getValueIn().date());
                break;
            case DATE_TIME:
                wire.getValueOut().dateTime(getValueIn().dateTime());
                break;
            case ZONED_DATE_TIME:
                wire.getValueOut().zonedDateTime(getValueIn().zonedDateTime());
                break;

            case TYPE_PREFIX: {
                long readPosition = bytes.readPosition();
                bytes.uncheckedReadSkipOne();
                @Nullable StringBuilder sb = readUtf8();
                if (StringUtils.isEqual("snappy", sb) || StringUtils.isEqual("gzip", sb) || StringUtils.isEqual("lzw", sb)) {
                    bytes.readPosition(readPosition);
                    wire.writeComment(sb);
                    wire.getValueOut().text(valueIn.text());
                } else {
                    wire.getValueOut().typePrefix(sb);
                }
                break;
            }

            case TYPE_LITERAL: {
                bytes.uncheckedReadSkipOne();
                @Nullable StringBuilder sb = readUtf8();
                wire.getValueOut().typeLiteral(sb);
                break;
            }

            case EVENT_NAME:
            case FIELD_NAME_ANY:
                @Nullable StringBuilder fsb = readField(peekCode, null, ANY_CODE_MATCH.code(), acquireStringBuilder(), true);
                wire.write(fsb);
                break;

            case EVENT_OBJECT:
                bytes.uncheckedReadSkipOne();
                wire.startEvent();
                wire.getValueOut().leaf(true);
                if (peekCode() == TYPE_PREFIX)
                    copyOne(wire);
                copyOne(wire);
                wire.endEvent();
                break;

            case STRING_ANY: {
                bytes.uncheckedReadSkipOne();
                @Nullable StringBuilder sb1 = readUtf8();
                wire.getValueOut().text(sb1);
                break;
            }

            case FIELD_NUMBER: {
                bytes.uncheckedReadSkipOne();
                long code2 = bytes.readStopBit();
                if (valueIn instanceof DeltaValueIn) {
                    @NotNull final DeltaValueIn din = (DeltaValueIn) this.valueIn;
                    if (code2 >= 0 && code2 < din.inField.length) {
                        String name = din.inField[(int) code2];
                        if (name != null) {
                            wire.write(name);
                            break;
                        }
                    }
                }
                wire.write(new WireKey() {
                    @NotNull
                    @Override
                    public String name() {
                        return Long.toString(code2);
                    }

                    @Override
                    public int code() {
                        return (int) code2;
                    }
                });
                break;
            }

            // Boolean
            case NULL:
                bytes.uncheckedReadSkipOne();
                wire.getValueOut().bool(null);
                break;

            case FALSE:
                bytes.uncheckedReadSkipOne();
                wire.getValueOut().bool(false);
                break;

            case TRUE:
                bytes.uncheckedReadSkipOne();
                wire.getValueOut().bool(true);
                break;
            default:
                unknownCode(wire);
        }
    }

    long readInt(int code) {
        if (code < 128)
            return code;
        switch (code >> 4) {
            case BinaryWireHighCode.SPECIAL:
                switch (code) {
                    case FALSE:
                        return 0;
                    case TRUE:
                        return 1;
                }
                break;

            case BinaryWireHighCode.FLOAT:
                double d = readFloat0(code);
                return (long) d;

            case BinaryWireHighCode.INT:
                return readInt0(code);
        }
        throw new UnsupportedOperationException(stringForCode(code));
    }

    double readFloat0(int code) {
        // TODO: in some places we have already called this before invoking the function,
        // so we should review them and optimize the calls to do the check only once
        if (code < 128 && code >= 0) {
            return code;
        }

        switch (code) {
            case FLOAT32:
                return bytes.readFloat();
            case FLOAT_STOP_2:
                return bytes.readStopBit() / 1e2;
            case FLOAT_STOP_4:
                return bytes.readStopBit() / 1e4;
            case FLOAT_STOP_6:
                return bytes.readStopBit() / 1e6;
            case FLOAT64:
                return bytes.readDouble();
        }
        throw new UnsupportedOperationException(stringForCode(code));
    }

    private Number readFloat0bject(int code) {
        // TODO: in some places we have already called this before invoking the function,
        // so we should review them and optimize the calls to do the check only once
        if (code < 128 && code >= 0) {
            return code;
        }

        switch (code) {
            case FLOAT32:
                return bytes.readFloat();
            case FLOAT_STOP_2:
                return bytes.readStopBit() / 1e2;
            case FLOAT_STOP_4:
                return bytes.readStopBit() / 1e4;
            case FLOAT_STOP_6:
                return bytes.readStopBit() / 1e6;
            case FLOAT64:
                return bytes.readDouble();
/*            case FIXED1:
                return bytes.readStopBit() / 1e1;
            case FIXED2:
                return bytes.readStopBit() / 1e2;
            case FIXED3:
                return bytes.readStopBit() / 1e3;
            case FIXED4:
                return bytes.readStopBit() / 1e4;
            case FIXED5:
                return bytes.readStopBit() / 1e5;
            case FIXED6:
                return bytes.readStopBit() / 1e6;*/
        }
        throw new UnsupportedOperationException(stringForCode(code));
    }

    long readInt0(int code) {
        if (isSmallInt(code))
            return code;

        switch (code) {
            case INT8:
                return bytes.readByte();
            case UINT8:
            case SET_LOW_INT8:
                return bytes.readUnsignedByte();
            case INT16:
                return bytes.readShort();
            case UINT16:
            case SET_LOW_INT16:
                return bytes.readUnsignedShort();
            case INT32:
                return bytes.readInt();
            case UINT32:
                return bytes.readUnsignedInt();
            case INT64:
            case INT64_0x:
                return bytes.readLong();
        }
        throw new UnsupportedOperationException(stringForCode(code));
    }

    Number readInt0object(int code) {
        if (isSmallInt(code))
            return code;

        switch (code) {
            case INT8:
                return bytes.readByte();
            case UINT8:
            case SET_LOW_INT8:
                return bytes.readUnsignedByte();
            case INT16:
                return bytes.readShort();
            case SET_LOW_INT16:
            case UINT16:
                return bytes.readUnsignedShort();
            case INT32:
                return bytes.readInt();
            case UINT32:
                return bytes.readUnsignedInt();
            case INT64:
            case INT64_0x:
                return bytes.readLong();
        }
        throw new UnsupportedOperationException(stringForCode(code));
    }

    private boolean isSmallInt(int code) {
        return (code & 128) == 0;
    }

    double readFloat(int code) {
        if (code < 128)
            return code;
        switch (code >> 4) {
            case BinaryWireHighCode.FLOAT:
                return readFloat0(code);

            case BinaryWireHighCode.INT:
                return readInt0(code);
        }
        throw new UnsupportedOperationException(stringForCode(code));
    }

    @NotNull
    @Override
    public ValueOut write() {
        if (!fieldLess) {
            writeField("");
        }
        return valueOut;
    }

    @NotNull
    @Override
    public ValueOut writeEventName(@NotNull WireKey key) {
        return writeEventName(key.name());
    }

    @NotNull
    @Override
    public ValueOut writeEventName(@NotNull CharSequence name) {
        writeCode(EVENT_NAME).write8bit(name);
        return valueOut;
    }

    @Override
    public void startEvent() {
        writeCode(EVENT_OBJECT);
    }

    @Override
    public void endEvent() {

    }

    @NotNull
    @Override
    public ValueOut write(@NotNull WireKey key) {
        if (!fieldLess) {
            if (numericFields)
                writeField(key.code());
            else
                writeField(key.name());
        }
        return valueOut;
    }

    @NotNull
    @Override
    public ValueOut write(@NotNull CharSequence key) {
        if (!fieldLess) {
            if (numericFields)
                writeField(WireKey.toCode(key));
            else
                writeField(key);
        }
        return valueOut;
    }

    @NotNull
    @Override
    public ValueOut getValueOut() {
        return valueOut;
    }

    @NotNull
    @Override
    public Wire writeComment(CharSequence s) {
        writeCode(COMMENT);
        bytes.writeUtf8(s);
        return BinaryWire.this;
    }

    @NotNull
    @Override
    public WireOut addPadding(int paddingToAdd) {
        if (paddingToAdd < 0)
            throw new IllegalStateException("Cannot add " + paddingToAdd + " bytes of padding");
        if (paddingToAdd >= 5) {
            writeCode(PADDING32)
                    .writeUnsignedInt(paddingToAdd - 5)
                    .writeSkip(paddingToAdd - 5);

        } else {
            for (int i = 0; i < paddingToAdd; i++)
                writeCode(PADDING);
        }
        return this;
    }

    private void writeField(@NotNull CharSequence name) {
        int len = name.length();
        if (len < 0x20) {
            writeField0(name, len);

        } else {
            writeCode(FIELD_NAME_ANY).write8bit(name);
        }
    }

    private void writeField0(@NotNull CharSequence name, int len) {
        if (len > 0 && Character.isDigit(name.charAt(0))) {
            try {
                writeField(Integer.parseInt(name.toString()));
                return;
            } catch (NumberFormatException ignored) {
            }
        }
        bytes.writeByte((byte) (FIELD_NAME0 + len));
        bytes.append8bit(name);
    }

    private void writeField(int code) {
        writeCode(FIELD_NUMBER);
        bytes.writeStopBit(code);
    }

    protected Bytes writeCode(int code) {
        return bytes.writeByte((byte) code);
    }

    @Nullable
    <ACS extends Appendable & CharSequence> ACS readText(int code, @NotNull ACS sb) {
        if (code <= 127) {
            AppendableUtil.append(sb, code);
            return sb;
        }
        switch (code >> 4) {
            case BinaryWireHighCode.CONTROL:
                switch (code) {
                    case BYTES_LENGTH8:
                    case BYTES_LENGTH16:
                    case BYTES_LENGTH32:
                        if (sb instanceof StringBuilder) {
                            bytes.uncheckedReadSkipBackOne();
                            valueIn.bytesStore((StringBuilder) sb);
                        } else if (sb instanceof Bytes) {
                            bytes.uncheckedReadSkipBackOne();
                            valueIn.bytesStore((Bytes) sb);
                        } else {
                            throw new IllegalArgumentException("Expected a StringBuilder or Bytes");
                        }
                        return sb;
                }
            case BinaryWireHighCode.SPECIAL:
                switch (code) {
                    case NULL:
                        AppendableUtil.append(sb, "null");
                        return sb;
                    case TRUE:
                        AppendableUtil.append(sb, "true");
                        return sb;
                    case FALSE:
                        AppendableUtil.append(sb, "false");
                        return sb;
                    case TIME:
                    case DATE:
                    case DATE_TIME:
                    case ZONED_DATE_TIME:
                    case TYPE_LITERAL:
                    case STRING_ANY:
                        if (bytes.readUtf8(sb))
                            return sb;
                        return null;
                    case EVENT_OBJECT:
                        valueIn.text((StringBuilder) sb);
                        return sb;
                    default:
                        return null;
                }

            case BinaryWireHighCode.FLOAT:
                AppendableUtil.append(sb, readFloat(code));
                return sb;
            case BinaryWireHighCode.INT:
                AppendableUtil.append(sb, readInt(code));
                return sb;
            case BinaryWireHighCode.STR0:
            case BinaryWireHighCode.STR1:
                return getStringBuilder(code, sb);
            default:
                throw new UnsupportedOperationException("code=0x" + String.format("%02X ", code).trim());
        }
    }

    int readCode() {
        return bytes.uncheckedReadUnsignedByte();
    }

    @NotNull
    public String toString() {
        return bytes.toDebugString();
    }

    @NotNull
    @Override
    public LongValue newLongReference() {
        return new BinaryLongReference();
    }

    @NotNull
    @Override
    public IntValue newIntReference() {
        return new BinaryIntReference();
    }

    @NotNull
    @Override
    public BinaryLongArrayReference newLongArrayReference() {
        return new BinaryLongArrayReference();
    }

    @Nullable
    StringBuilder readUtf8() {
        @NotNull StringBuilder sb = acquireStringBuilder();
        return bytes.readUtf8(sb) ? sb : null;
    }

    enum AnyCodeMatch implements WireKey {
        ANY_CODE_MATCH;

        public int code() {
            return Integer.MIN_VALUE;
        }
    }

    protected class FixedBinaryValueOut implements ValueOut {
        @NotNull
        @Override
        public ValueOut leaf() {
            return this;
        }

        @NotNull
        @Override
        public WireOut bool(@Nullable Boolean flag) {
            bytes.writeUnsignedByte(flag == null
                    ? NULL
                    : (flag ? TRUE : FALSE));
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public WireOut nu11() {
            writeCode(NULL);
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public WireOut text(@Nullable CharSequence s) {
            if (s == null) {
                nu11();

            } else {
                int len = s.length();
                if (len < 0x20) {
                    bytes.writeUnsignedByte(STRING_0 + len).appendUtf8(s);
                } else {
                    writeCode(STRING_ANY);
                    bytes.writeUtf8(s);
                }
            }

            return BinaryWire.this;
        }

        @NotNull
        @Override
        public WireOut text(@Nullable String s) {
            if (s == null) {
                writeCode(NULL);

            } else {
                int len = s.length();
                if (len < 0x20)
                    len = (int) AppendableUtil.findUtf8Length(s);

                if (len < 0x20) {
                    bytes.writeUnsignedByte((int) (STRING_0 + len)).appendUtf8(StringUtils.extractChars(s), 0, s.length());

                } else {
                    writeCode(STRING_ANY);
                    bytes.writeUtf8(s);
                }
            }

            return BinaryWire.this;
        }

        @NotNull
        @Override
        public WireOut text(@Nullable BytesStore s) {
            if (s == null) {
                writeCode(NULL);

            } else {
                int len = s.length();
                if (len < 0x20) {
                    bytes.writeUnsignedByte(STRING_0 + len).appendUtf8(s);
                } else {
                    writeCode(STRING_ANY).writeUtf8(s);
                }
            }

            return BinaryWire.this;
        }

        @NotNull
        @Override
        public WireOut bytes(@Nullable BytesStore fromBytes) {
            if (fromBytes == null)
                return nu11();
            long remaining = fromBytes.readRemaining();
            if (remaining >= compressedSize()) {
                compress(compression, fromBytes.bytesForRead());
            } else {
                bytes0(fromBytes, remaining);
            }
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public WireOut bytesLiteral(@Nullable BytesStore fromBytes) {
            long remaining = fromBytes.readRemaining();
            writeLength(Maths.toInt32(remaining));
            bytes.write(fromBytes);
            return BinaryWire.this;
        }

        @Override
        public int compressedSize() {
            return compressedSize;
        }

        public void bytes0(@Nullable BytesStore fromBytes, long remaining) {
            writeLength(Maths.toInt32(remaining + 1));
            writeCode(U8_ARRAY);
            if (remaining > 0)
                bytes.write(fromBytes);
        }

        @NotNull
        @Override
        public WireOut rawBytes(@NotNull byte[] value) {
            typePrefix(byte[].class);
            writeLength(Maths.toInt32(value.length + 1));
            writeCode(U8_ARRAY);
            if (value.length > 0)
                bytes.write(value);
            return BinaryWire.this;
        }

        @NotNull
        public ValueOut writeLength(long length) {
            if (length < 0) {
                throw new IllegalArgumentException("Invalid length " + length);

            } else if (length < 1 << 8) {
                writeCode(BYTES_LENGTH8);
                bytes.writeUnsignedByte((int) length);

            } else if (length < 1 << 16) {
                writeCode(BYTES_LENGTH16);
                bytes.writeUnsignedShort((int) length);

            } else {
                writeCode(BYTES_LENGTH32);
                bytes.writeUnsignedInt(length);
            }
            return this;
        }

        @NotNull
        @Override
        public WireOut bytes(@NotNull byte[] fromBytes) {
            writeLength(Maths.toInt32(fromBytes.length + 1));
            writeCode(U8_ARRAY);
            bytes.write(fromBytes);
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public WireOut bytes(String type, @Nullable BytesStore fromBytes) {
            typePrefix(type);
            if (fromBytes != null)
                bytes0(fromBytes, fromBytes.readRemaining());
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public WireOut bytes(String type, @NotNull byte[] fromBytes) {
            typePrefix(type);
            return bytes(fromBytes);
        }

        @NotNull
        @Override
        public WireOut int8(byte i8) {
            return fixedInt8(i8);
        }

        @NotNull
        public WireOut fixedInt8(byte i8) {
            writeCode(INT8).writeByte(i8);
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public WireOut uint8checked(int u8) {
            writeCode(UINT8).writeUnsignedByte(u8);
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public WireOut int16(short i16) {
            return fixedInt16(i16);
        }

        @NotNull
        public WireOut fixedInt16(short i16) {
            writeCode(INT16).writeShort(i16);
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public WireOut uint16checked(int u16) {
            writeCode(UINT16).writeUnsignedShort(u16);
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public WireOut utf8(int codepoint) {
            writeCode(UINT16);
            bytes.appendUtf8(codepoint);
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public WireOut int32(int i32) {
            return fixedInt32(i32);
        }

        @NotNull
        public WireOut fixedInt32(int i32) {
            writeCode(INT32).writeInt(i32);
            return BinaryWire.this;
        }

        @NotNull
        public WireOut fixedOrderedInt32(int i32) {
            writeCode(INT32).writeOrderedInt(i32);
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public WireOut uint32checked(long u32) {
            writeCode(UINT32).writeUnsignedInt(u32);
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public WireOut int64(long i64) {
            return fixedInt64(i64);
        }

        @NotNull
        public WireOut fixedInt64(long i64) {
            writeCode(INT64).writeLong(i64);
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public WireOut int64_0x(long i64) {
            writeCode(INT64_0x).writeLong(i64);
            return BinaryWire.this;
        }

        @NotNull
        private WireOut fixedOrderedInt64(long i64) {
            writeAlignTo(8, 1);
            writeCode(INT64).writeOrderedLong(i64);
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public WireOut int64array(long capacity) {
            writeAlignTo(8, 1);
            writeCode(I64_ARRAY);
            BinaryLongArrayReference.lazyWrite(bytes, capacity);
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public WireOut int64array(long capacity, @NotNull LongArrayValues values) {
            writeAlignTo(8, 1);
            writeCode(I64_ARRAY);
            long pos = bytes.writePosition();
            BinaryLongArrayReference.lazyWrite(bytes, capacity);
            ((Byteable) values).bytesStore(bytes, pos, bytes.writePosition() - pos);
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public WireOut float32(float f) {
            return fixedFloat32(f);
        }

        @NotNull
        public WireOut fixedFloat32(float f) {
            writeCode(FLOAT32).writeFloat(f);
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public WireOut float64(double d) {
            return fixedFloat64(d);
        }

        @NotNull
        public WireOut fixedFloat64(double d) {
            writeCode(FLOAT64).writeDouble(d);
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public WireOut time(@NotNull LocalTime localTime) {
            writeCode(TIME).writeUtf8(localTime.toString());
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public WireOut zonedDateTime(@NotNull ZonedDateTime zonedDateTime) {
            writeCode(ZONED_DATE_TIME).writeUtf8(zonedDateTime.toString());
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public WireOut date(@NotNull LocalDate localDate) {
            writeCode(DATE).writeUtf8(localDate.toString());
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public WireOut dateTime(@NotNull LocalDateTime localDateTime) {
            writeCode(DATE_TIME).writeUtf8(localDateTime.toString());
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public ValueOut typePrefix(CharSequence typeName) {
            writeCode(TYPE_PREFIX).writeUtf8(typeName);
            return this;
        }

        @NotNull
        @Override
        public WireOut typeLiteral(@NotNull CharSequence type) {
            writeCode(TYPE_LITERAL).writeUtf8(type);
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public WireOut typeLiteral(@Nullable Class type) {
            if (type == null)
                nu11();
            else
                writeCode(TYPE_LITERAL).writeUtf8(classLookup().nameFor(type));
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public WireOut typeLiteral(@NotNull BiConsumer<Class, Bytes> typeTranslator, @NotNull Class type) {
            writeCode(TYPE_LITERAL);
            typeTranslator.accept(type, bytes);
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public WireOut uuid(@NotNull UUID uuid) {
            writeCode(UUID).writeLong(uuid.getMostSignificantBits()).writeLong(uuid.getLeastSignificantBits());
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public WireOut int32forBinding(int value) {
            int fromEndOfCacheLine = (int) ((-bytes.readPosition() - 1) & 63);
            if (fromEndOfCacheLine < 4)
                addPadding(fromEndOfCacheLine - 1);
            fixedInt32(value);
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public WireOut int64forBinding(long value) {
            int fromEndOfCacheLine = (int) ((-bytes.readPosition() - 1) & 63);
            if (fromEndOfCacheLine < 8)
                addPadding(fromEndOfCacheLine);
            fixedOrderedInt64(value);
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public WireOut int32forBinding(int value, @NotNull IntValue intValue) {
            int32forBinding(value);
            ((BinaryIntReference) intValue).bytesStore(bytes, bytes.writePosition() - 4, 4);
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public WireOut int64forBinding(long value, @NotNull LongValue longValue) {
            int64forBinding(value);
            ((BinaryLongReference) longValue).bytesStore(bytes, bytes.writePosition() - 8, 8);
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public <T> WireOut sequence(T t, @NotNull BiConsumer<T, ValueOut> writer) {
            writeCode(BYTES_LENGTH32);
            long position = bytes.writePosition();
            bytes.writeInt(0);

            writer.accept(t, this);

            bytes.writeOrderedInt(position, Maths.toInt32(bytes.writePosition() - position - 4, "Document length %,d out of 32-bit int range."));
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public <T, K> WireOut sequence(T t, K kls, @NotNull TriConsumer<T, K, ValueOut> writer) {
            writeCode(BYTES_LENGTH32);
            long position = bytes.writePosition();
            bytes.writeInt(0);

            writer.accept(t, kls, this);

            bytes.writeOrderedInt(position, Maths.toInt32(bytes.writePosition() - position - 4, "Document length %,d out of 32-bit int range."));
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public WireOut marshallable(@NotNull WriteMarshallable object) {
            writeCode(BYTES_LENGTH32);
            long position = bytes.writePosition();
            bytes.writeInt(0);

            object.writeMarshallable(BinaryWire.this);

            bytes.writeOrderedInt(position, Maths.toInt32(bytes.writePosition() - position - 4, "Document length %,d out of 32-bit int range."));
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public WireOut marshallable(@NotNull Serializable object) {
            writeCode(BYTES_LENGTH32);
            long position = bytes.writePosition();
            bytes.writeInt(0);

            try {
                if (object instanceof Externalizable) {
                    ((Externalizable) object).writeExternal(objectOutput());
                } else {
                    Wires.writeMarshallable(object, BinaryWire.this);
                }
            } catch (IOException e) {
                throw new IORuntimeException(e);
            }

            bytes.writeOrderedInt(position, Maths.toInt32(bytes.writePosition() - position - 4, "Document length %,d out of 32-bit int range."));
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public WireOut map(Map map) {
            return marshallable(map);
        }

        @NotNull
        @Override
        public WireOut typedMap(@NotNull Map<? extends WriteMarshallable, ? extends Marshallable> map) {
            throw new UnsupportedOperationException("todo");
        }

        @NotNull
        @Override
        public WireOut wireOut() {
            return BinaryWire.this;
        }

        public void resetState() {

        }
    }

    protected class BinaryValueOut extends FixedBinaryValueOut {
        @NotNull
        @Override
        public WireOut int8(byte i8) {
            writeNumber(i8);
            return BinaryWire.this;
        }

        void writeNumber(long l) {
            switch (Long.numberOfLeadingZeros(l)) {
                case 64:
                case 63:
                case 62:
                case 61:
                case 60:
                case 59:
                case 58:
                case 57:
                    // used when the value is written directly into the code byte
                    bytes.writeUnsignedByte((int) l);
                    return;
                case 56:
                    super.uint8checked((short) l);
                    return;
                case 55:
                case 54:
                case 53:
                case 52:
                case 51:
                case 50:
                case 49:
                    super.fixedInt16((short) l);
                    return;
                case 48:
                    super.uint16checked((int) l);
                    return;
                case 47:
                case 46:
                case 45:
                case 44:
                case 43:
                case 42:
                case 41:
                case 40:
                case 39:
                case 38:
                case 37:
                case 36:
                case 35:
                case 34:
                case 33:
                    super.fixedInt32((int) l);
                    return;
                case 32:
                    super.uint32checked(l);
                    return;
                case 0:
                    if (l >= Byte.MIN_VALUE) {
                        super.int8((byte) l);
                        return;
                    }

                    if (l >= Short.MIN_VALUE) {
                        super.int16((short) l);
                        return;
                    }

                    if (l >= Integer.MIN_VALUE) {
                        super.int32((int) l);
                        return;
                    }
                    break;
            }

            if ((long) (float) l == l) {
                super.float32(l);
                return;
            }

            super.int64(l);
        }

        void writeNumber(double l) {

            boolean canOnlyBeRepresentedAsFloatingPoint = ((long) l) != l;
            if (canOnlyBeRepresentedAsFloatingPoint) {

                if (((double) (float) l) == l) {
                    super.float32((float) l);
                    return;
                }

                if (SPEC >= 18) {
                    long l6 = Math.round(l * 1e6);
                    if (l6 / 1e6 == l && l6 > (-1L << 35) && l6 < (1L << 42)) {
                        long i2 = l6 / 10000;
                        if (i2 / 1e2 == l) {
                            writeCode(FLOAT_STOP_2).writeStopBit(i2);
                            return;
                        }

                        long i4 = l6 / 100;
                        if (i4 / 1e4 == l) {
                            writeCode(FLOAT_STOP_4).writeStopBit(i4);
                            return;
                        }

                        if (l6 / 1e6 == l) {
                            writeCode(FLOAT_STOP_6).writeStopBit(l6);
                            return;
                        }
                    }
                }

            } else {

                if (l >= 0 && l <= 127) {
                    // used when the value is written directly into the code byte
                    bytes.writeUnsignedByte((int) l);
                    return;
                }

                if (l >= 0) {

                    if (l <= (1 << 8) - 1) {
                        super.uint8checked((short) l);
                        return;
                    }

                    if (l <= (1 << 16) - 1) {
                        super.uint16checked((int) l);
                        return;
                    }

                    if ((float) l == l) {
                        super.float32((float) l);
                        return;
                    }

                    if (l <= (1L << 32L) - 1) {
                        super.uint32checked((int) l);
                        return;
                    }

                    super.float64(l);
                    return;
                }

                if (l >= Byte.MIN_VALUE && l <= Byte.MAX_VALUE) {
                    super.int8((byte) l);
                    return;
                }

                if (l >= Short.MIN_VALUE && l <= Short.MAX_VALUE) {
                    super.int16((short) l);
                    return;
                }

                if ((float) l == l) {
                    super.float32((float) l);
                    return;
                }

                if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                    super.int32((int) l);
                    return;
                }
            }
            super.float64(l);
        }

        @NotNull
        @Override
        public WireOut uint8checked(int u8) {
            writeNumber(u8);
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public WireOut int16(short i16) {
            writeNumber(i16);
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public WireOut uint16checked(int u16) {
            writeNumber(u16);
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public WireOut int32(int i32) {
            writeNumber(i32);
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public WireOut uint32checked(long u32) {
            writeNumber(u32);
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public WireOut int64(long i64) {
            writeNumber(i64);
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public WireOut float32(float f) {
            writeNumber(f);
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public WireOut float64(double d) {
            writeNumber(d);
            return BinaryWire.this;
        }
    }

    protected class BinaryValueIn implements ValueIn {
        final ValueInStack stack = new ValueInStack();

        @Override
        public void resetState() {
            stack.reset();
        }

        public void pushState() {
            stack.push();
        }

        public void popState() {
            stack.pop();
        }

        public ValueInState curr() {
            return stack.curr();
        }

        @NotNull
        @Override
        public BracketType getBracketType() {
            consumePadding();
            switch (peekCode()) {
                case BYTES_LENGTH16:
                    return getBracketTypeFor(bytes.readUnsignedByte(bytes.readPosition() + 2 + 1));
                case BYTES_LENGTH32:
                    return getBracketTypeFor(bytes.readUnsignedByte(bytes.readPosition() + 4 + 1));
            }
            return BracketType.NONE;
        }

        @NotNull
        WireIn text(@NotNull Consumer<String> s) {
            consumePadding();
            int code = readCode();
            switch (code) {
                case NULL:
                    s.accept(null);
                    break;

                case STRING_ANY:
                    s.accept(bytes.readUtf8());
                    break;
                default:
                    if (code >= STRING_0 && code <= STRING_31) {
                        @NotNull StringBuilder sb = acquireStringBuilder();
                        bytes.parseUtf8(sb, code & 0b11111);
                        s.accept(WireInternal.INTERNER.intern(sb));

                    } else {
                        cantRead(code);
                    }
            }
            return BinaryWire.this;
        }

        private boolean isText(int code) {
            return code == STRING_ANY ||
                    (code >= STRING_0 && code <= STRING_31);
        }

        @Nullable
        @Override
        public StringBuilder textTo(@NotNull StringBuilder sb) {
            int code = readCode();
            boolean wasNull = code == NULL;
            if (wasNull) {
                sb.setLength(0);
                return null;

            } else {
                @Nullable StringBuilder text = readText(code, sb);
                if (text == null)
                    cantRead(code);
                return sb;
            }
        }

        @Nullable
        @Override
        public Bytes textTo(@NotNull Bytes bytes) {
            int code = readCode();
            boolean wasNull = code == NULL;
            if (wasNull) {
                bytes.readPosition(0);
                return null;

            } else {
                @Nullable Bytes text = readText(code, bytes);
                if (text == null)
                    cantRead(code);
                return bytes;
            }
        }

        @Nullable
        @Override
        public String text() {
            int code = readCode();
            switch (code) {
                case NULL:
                    return null;

                case STRING_ANY: {
                    long len0 = bytes.readStopBit();
                    if (len0 == -1L) {
                        return null;

                    }
                    int len = Maths.toUInt31(len0);
                    long limit = bytes.readLimit();
                    long end = bytes.readPosition() + len;
                    try {
                        bytes.readLimit(end);
                        return UTF8.intern(bytes);
                    } finally {
                        bytes.readLimit(limit);
                        bytes.readPosition(end);
                    }
                }

                case TYPE_PREFIX: {
                    @Nullable StringBuilder sb = readUtf8();
                    if (sb != null) {
                        @Nullable byte[] bytes = Compression.uncompress(sb, this, ValueIn::bytes);
                        if (bytes != null)
                            return new String(bytes, StandardCharsets.UTF_8);
                    }
                    @Nullable StringBuilder text = readText(code, acquireStringBuilder());
                    return WireInternal.INTERNER.intern(text);
                }

                default: {
                    StringBuilder sb = acquireStringBuilder();
                    @Nullable StringBuilder text = ((code & 0xE0) == 0xE0)
                            ? getStringBuilder(code, sb)
                            : readText(code, sb);
                    return text == null ? null : WireInternal.INTERNER.intern(text);
                }
            }
        }

        @NotNull
        public WireIn bytes(@NotNull BytesOut toBytes) {
            return bytes(toBytes, true);
        }

        @Override
        public WireIn bytes(@NotNull BytesOut toBytes, boolean clearBytes) {
            long length = readLength();
            int code = readCode();
            if (code == NULL) {
                ((BytesStore) toBytes).isPresent(false);
                return BinaryWire.this;
            }
            if (code == TYPE_PREFIX) {
                @Nullable StringBuilder sb = readUtf8();
                assert sb != null;

                long length2 = readLength();
                int code2 = readCode();
                if (code2 != U8_ARRAY)
                    cantRead(code);
                if (clearBytes)
                    toBytes.clear();
                bytes.readWithLength(length2 - 1, b -> Compression.uncompress(sb, b, toBytes));
                return wireIn();

            }
            if (code != U8_ARRAY)
                cantRead(code);
            if (clearBytes)
                toBytes.clear();
            bytes.readWithLength(length - 1, toBytes::write);
            return wireIn();
        }

        @NotNull
        @Override
        public WireIn bytesLiteral(@NotNull BytesOut toBytes) {
            long length = readLength();
            toBytes.clear();
            toBytes.write(bytes, bytes.readPosition(), length);
            bytes.readSkip(length);
            return wireIn();
        }

        @NotNull
        @Override
        public BytesStore bytesLiteral() {
            int length = Maths.toUInt31(readLength());
            @NotNull BytesStore toBytes = BytesStore.wrap(new byte[length]);
            toBytes.write(0, bytes, bytes.readPosition(), length);
            bytes.readSkip(length);
            return toBytes;
        }

        @Nullable
        public WireIn bytesSet(@NotNull PointerBytesStore toBytes) {
            long length = readLength();
            int code = readCode();
            if (code == NULL) {
                toBytes.isPresent(false);
                return BinaryWire.this;
            }
            if (code != U8_ARRAY)
                cantRead(code);
            long startAddr = bytes.address(bytes.readPosition());
            toBytes.set(startAddr, length - 1);
            bytes.readSkip(length - 1);
            return wireIn();
        }

        @NotNull
        @Override
        public WireIn bytesMatch(@NotNull BytesStore compareBytes, @NotNull BooleanConsumer consumer) {
            long length = readLength();
            int code = readCode();
            if (code != U8_ARRAY)
                cantRead(code);
            length--;
            if (compareBytes.readRemaining() == length) {
                consumer.accept(bytes.equalBytes(compareBytes, length));
            } else {
                consumer.accept(false);
            }
            bytes.readSkip(length);
            return wireIn();

        }

        @Nullable
        public BytesStore bytesStore() {
            long length = readLength() - 1;
            int code = readCode();
            switch (code) {
                case I64_ARRAY:
                case U8_ARRAY:
                    @NotNull BytesStore toBytes = NativeBytesStore.lazyNativeBytesStoreWithFixedCapacity(length);
                    toBytes.write(0, bytes, bytes.readPosition(), length);
                    bytes.readSkip(length);
                    return toBytes;

                case TYPE_PREFIX: {
                    @Nullable StringBuilder sb = readUtf8();
                    @Nullable byte[] bytes = Compression.uncompress(sb, this, ValueIn::bytes);
                    if (bytes != null)
                        return BytesStore.wrap(bytes);
                    throw new UnsupportedOperationException("Unsupported type " + sb);
                }
                case NULL:
                    return null;

                default:
                    cantRead(code);
                    throw new AssertionError();
            }
        }

        public void bytesStore(@NotNull StringBuilder sb) {
            sb.setLength(0);
            consumePadding();
            long pos = bytes.readPosition();
            long length = readLength();
            if (length < 0)
                throw cantRead(peekCode());

            int code = readCode();
            if (code == U8_ARRAY) {
                for (long i = 1; i < length; i++)
                    sb.append((char) bytes.readUnsignedByte());
            } else {
                bytes.readPosition(pos);
                long limit = bytes.readLimit();
                bytes.readLimit(pos + 4 + length);
                try {
                    sb.append(Wires.fromSizePrefixedBlobs(bytes));
                } finally {
                    bytes.readLimit(limit);
                    bytes.readPosition(limit);
                }
            }
        }

        public void bytesStore(@NotNull Bytes toBytes) {
            toBytes.clear();
            long length = readLength() - 1;
            int code = readCode();
            if (code == NULL) {
                toBytes.isPresent(false);
                return;
            }
            if (code != U8_ARRAY)
                cantRead(code);
            toBytes.write(0, bytes, bytes.readPosition(), length);
            toBytes.readLimit(length);
            bytes.readSkip(length);
        }

        @NotNull
        public WireIn bytes(@NotNull ReadBytesMarshallable bytesConsumer) {
            long length = readLength() - 1;
            int code = readCode();
            if (code != U8_ARRAY)
                cantRead(code);

            if (length > bytes.readRemaining())
                throw new BufferUnderflowException();
            long limit0 = bytes.readLimit();
            long limit = bytes.readPosition() + length;
            try {
                bytes.readLimit(limit);
                bytesConsumer.readMarshallable(bytes);
            } finally {
                bytes.readLimit(limit0);
                bytes.readPosition(limit);
            }
            return wireIn();
        }

        @NotNull
        @Override
        public byte[] bytes() {
            long length = readLength();
            int code = readCode();
            if (code == NULL) {
                return null;
            }

            if (code == TYPE_PREFIX) {
                @Nullable StringBuilder sb = readUtf8();
                assert "byte[]".contentEquals(sb);
                length = readLength();
                code = readCode();
            }

            if (code != U8_ARRAY)
                cantRead(code);
            @NotNull byte[] bytes2 = new byte[Maths.toUInt31(length - 1)];
            bytes.readWithLength(length - 1, b -> b.read(bytes2));
            return bytes2;
        }

        @NotNull
        @Override
        public WireIn wireIn() {
            return BinaryWire.this;
        }

        @Override
        public long readLength() {
            // TODO handle non length types as well.

            int code = peekCode();
            switch (code) {

                case BYTES_LENGTH8:
                    bytes.uncheckedReadSkipOne();
                    return bytes.uncheckedReadUnsignedByte();

                case BYTES_LENGTH16:
                    bytes.uncheckedReadSkipOne();
                    return bytes.readUnsignedShort();

                case BYTES_LENGTH32:
                    bytes.uncheckedReadSkipOne();
                    return bytes.readUnsignedInt();

                case TYPE_PREFIX:
                    bytes.uncheckedReadSkipOne();
                    long len = bytes.readStopBit();
                    bytes.readSkip(len);
                    return readLength();
                case FALSE:
                case TRUE:
                case UINT8:
                case INT8:
                case FLOAT_SET_LOW_0:
                case FLOAT_SET_LOW_2:
                case FLOAT_SET_LOW_4:
                    return 1;
                case UINT16:
                case INT16:
                    return 2;
                case FLOAT32:
                case UINT32:
                case INT32:
                    return 4;
                case FLOAT64:
                case INT64:
                    return 8;

                case PADDING:
                case PADDING32:
                case COMMENT:
                    consumePadding();
                    return readLength();

                case FLOAT_STOP_2:
                case FLOAT_STOP_4:
                case FLOAT_STOP_6: {
                    if (bytes.readRemaining() > 1) {
                        byte b = bytes.readByte(bytes.readPosition() + 1);
                        return b >= 0 ? 1 : -1;
                    }
                    return 1;
                }
                case -1:
                    return 0;

                default:
                    //System.out.println("code=" + code + ", bytes=" + bytes.toHexString());
                    return -1;
            }

        }

        @NotNull
        @Override
        public WireIn skipValue() {
            final long length = readLength();
            if (length < 0)
                object();
            else
                bytes.readSkip(length);

            return BinaryWire.this;
        }

        @NotNull
        @Override
        public <T> WireIn bool(T t, @NotNull ObjBooleanConsumer<T> tFlag) {
            consumePadding();
            int code = readCode();
            switch (code) {
                case NULL:
                    // todo take the default.
                    tFlag.accept(t, null);
                    break;

                case FALSE:
                    tFlag.accept(t, false);
                    break;

                case TRUE:
                    tFlag.accept(t, true);
                    break;
                default:
                    throw cantRead(code);
            }
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public <T> WireIn int8(@NotNull T t, @NotNull ObjByteConsumer<T> tb) {
            consumePadding();

            final int code = bytes.readUnsignedByte();

            if (isText(code))
                tb.accept(t, Byte.parseByte(text()));
            else
                tb.accept(t, (byte) readInt(code));

            return BinaryWire.this;
        }

        @NotNull
        @Override
        public <T> WireIn uint8(@NotNull T t, @NotNull ObjShortConsumer<T> ti) {
            consumePadding();

            final int code = readCode();
            if (isText(code))
                ti.accept(t, Short.parseShort(text()));
            else
                ti.accept(t, (short) readInt(code));
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public <T> WireIn int16(@NotNull T t, @NotNull ObjShortConsumer<T> ti) {
            final int code = readCode();
            if (isText(code))
                ti.accept(t, Short.parseShort(text()));
            else
                ti.accept(t, (short) readInt(code));
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public <T> WireIn uint16(@NotNull T t, @NotNull ObjIntConsumer<T> ti) {
            consumePadding();
            final int code = readCode();
            if (isText(code))
                ti.accept(t, Integer.parseInt(text()));
            else
                ti.accept(t, (int) readInt(code));
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public <T> WireIn int32(@NotNull T t, @NotNull ObjIntConsumer<T> ti) {
            consumePadding();
            final int code = readCode();
            if (isText(code))
                ti.accept(t, Integer.parseInt(text()));
            else
                ti.accept(t, (int) readInt(code));
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public <T> WireIn uint32(@NotNull T t, @NotNull ObjLongConsumer<T> tl) {
            consumePadding();
            final int code = readCode();
            if (isText(code))
                tl.accept(t, Long.parseLong(text()));
            else
                tl.accept(t, readInt(code));
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public <T> WireIn int64(@NotNull T t, @NotNull ObjLongConsumer<T> tl) {
            final int code = readCode();
            if (isText(code))
                tl.accept(t, Long.parseLong(text()));
            else
                tl.accept(t, readInt(code));
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public <T> WireIn float32(@NotNull T t, @NotNull ObjFloatConsumer<T> tf) {
            consumePadding();
            final int code = readCode();
            if (isText(code))
                tf.accept(t, Float.parseFloat(text()));
            else
                tf.accept(t, (float) readFloat(code));
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public <T> WireIn float64(@NotNull T t, @NotNull ObjDoubleConsumer<T> td) {
            consumePadding(false);
            final int code = readCode();
            td.accept(t, readFloat(code));
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public <T> WireIn time(@NotNull T t, @NotNull BiConsumer<T, LocalTime> setLocalTime) {
            consumePadding();
            int code = readCode();
            if (code == TIME) {
                setLocalTime.accept(t, readLocalTime());

            } else {
                cantRead(code);
            }
            return BinaryWire.this;
        }

        private LocalTime readLocalTime() {
            @Nullable StringBuilder sb = readUtf8();
            return LocalTime.parse(sb);
        }

        @NotNull
        @Override
        public <T> WireIn zonedDateTime(@NotNull T t, @NotNull BiConsumer<T, ZonedDateTime> tZonedDateTime) {
            consumePadding();
            int code = readCode();
            if (code == ZONED_DATE_TIME) {
                @Nullable StringBuilder sb = readUtf8();
                tZonedDateTime.accept(t, ZonedDateTime.parse(sb));

            } else {
                cantRead(code);
            }
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public <T> WireIn date(@NotNull T t, @NotNull BiConsumer<T, LocalDate> tLocalDate) {
            consumePadding();
            int code = readCode();
            if (code == DATE) {
                @Nullable StringBuilder sb = readUtf8();
                tLocalDate.accept(t, LocalDate.parse(sb));

            } else {
                cantRead(code);
            }
            return BinaryWire.this;
        }

        @Override
        public boolean hasNext() {
            return bytes.readRemaining() > 0;
        }

        @Override
        public boolean hasNextSequenceItem() {
            return hasNext();
        }

        @NotNull
        @Override
        public UUID uuid() {
            consumePadding();
            int code = readCode();
            if (code == UUID) {
                return new UUID(bytes.readLong(), bytes.readLong());

            } else {
                throw cantRead(code);
            }
        }

        @NotNull
        @Override
        public <T> WireIn uuid(@NotNull T t, @NotNull BiConsumer<T, UUID> tuuid) {
            consumePadding();
            int code = readCode();
            if (code == UUID) {
                tuuid.accept(t, new UUID(bytes.readLong(), bytes.readLong()));

            } else {
                cantRead(code);
            }
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public <T> WireIn int64array(@Nullable LongArrayValues values, T t, @NotNull BiConsumer<T, LongArrayValues> setter) {
            consumePadding();
            int code = readCode();
            if (code == I64_ARRAY) {
                if (!(values instanceof BinaryLongArrayReference))
                    setter.accept(t, values = new BinaryLongArrayReference());
                @Nullable Byteable b = (Byteable) values;
                long length = BinaryLongArrayReference.peakLength(bytes, bytes.readPosition());
                b.bytesStore(bytes, bytes.readPosition(), length);
                bytes.readSkip(length);

            } else {
                cantRead(code);
            }
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public WireIn int64(LongValue value) {
            consumePadding();
            int code = readCode();
            if (code != INT64)
                cantRead(code);

            @NotNull Byteable b = (Byteable) value;
            long length = b.maxSize();
            b.bytesStore(bytes, bytes.readPosition(), length);
            bytes.readSkip(length);
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public WireIn int32(IntValue value) {
            consumePadding();
            int code = readCode();
            if (code != INT32)
                cantRead(code);

            @NotNull Byteable b = (Byteable) value;
            long length = b.maxSize();
            b.bytesStore(bytes, bytes.readPosition(), length);
            bytes.readSkip(length);
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public <T> WireIn int64(@Nullable LongValue value, T t, @NotNull BiConsumer<T, LongValue> setter) {
            // if the value is null, then we will create a LongDirectReference to write the data
            // into and then call setter.accept(), this will then update the value
            if (!(value instanceof BinaryLongReference)) {
                setter.accept(t, value = new BinaryLongReference());
            }
            return int64(value);
        }

        @NotNull
        @Override
        public <T> WireIn int32(@Nullable IntValue value, T t, @NotNull BiConsumer<T, IntValue> setter) {
            consumePadding();
            int code = readCode();
            if (code != INT32)
                cantRead(code);
            if (!(value instanceof Byteable) || ((Byteable) value).maxSize() != 4) {
                setter.accept(t, value = new BinaryIntReference());
            }
            @NotNull Byteable b = (Byteable) value;
            long length = b.maxSize();
            b.bytesStore(bytes, bytes.readPosition(), length);
            bytes.readSkip(length);
            return BinaryWire.this;
        }

        @NotNull
        @Override
        public <T> boolean sequence(@NotNull T t, @NotNull BiConsumer<T, ValueIn> tReader) {
            if (isNull())
                return false;
            long length = readLength();
            if (length < 0)
                throw cantRead(peekCode());

            long limit = bytes.readLimit();
            long limit2 = bytes.readPosition() + length;
            bytes.readLimit(limit2);
            try {
                tReader.accept(t, this);
            } finally {
                bytes.readLimit(limit);
                bytes.readPosition(limit2);
            }
            return true;
        }

        @NotNull
        @Override
        public <T, K> WireIn sequence(@NotNull T t, K kls, @NotNull TriConsumer<T, K, ValueIn> tReader) {
            consumePadding();
            int code = readCode();
            if (code != BYTES_LENGTH32)
                cantRead(code);
            final int length = bytes.readInt();
            long limit = bytes.readLimit();
            long limit2 = bytes.readPosition() + length;
            bytes.readLimit(limit2);
            try {
                tReader.accept(t, kls, this);
            } finally {
                bytes.readLimit(limit);
                bytes.readPosition(limit2);
            }
            return BinaryWire.this;
        }

        @Override
        public <T> T applyToMarshallable(@NotNull Function<WireIn, T> marshallableReader) {
            consumePadding();
            pushState();
            try {
                long length = readLength();
                if (length >= 0) {
                    long limit = bytes.readLimit();
                    long limit2 = bytes.readPosition() + length;
                    bytes.readLimit(limit2);
                    try {
                        return marshallableReader.apply(BinaryWire.this);
                    } finally {
                        bytes.readLimit(limit);
                        bytes.readPosition(limit2);
                    }
                } else {
                    return marshallableReader.apply(BinaryWire.this);
                }
            } finally {
                popState();
            }
        }

        @Override
        public boolean isTyped() {
            int code = peekCode();
            return code == TYPE_PREFIX;
        }

        @Nullable
        public <T> T typedMarshallable() throws IORuntimeException {
            pushState();
            try {
                int code = readCode();
                switch (code) {
                    case TYPE_PREFIX:
                        return typedMarshallable0();

                    case NULL:
                        return null;

                    case ANCHOR:
                        return anchor();

                    case UPDATED_ALIAS:
                        return updateAlias();

                    default:
                        cantRead(code);
                        return null; // only if the throw doesn't work.
                }
            } finally {
                popState();
            }
        }

        @Nullable
        protected <T> T typedMarshallable0() {
            @Nullable StringBuilder sb = readUtf8();
            // its possible that the object that you are allocating may not have a
            // default constructor
            final Class clazz;
            try {
                clazz = classLookup().forName(sb);
            } catch (ClassNotFoundException e) {
                throw new IORuntimeException(e);
            }

            if (Demarshallable.class.isAssignableFrom(clazz)) {
                return (T) demarshallable(clazz);
            }
            if (!Marshallable.class.isAssignableFrom(clazz) && !Demarshallable.class.isAssignableFrom(clazz))
                throw new IllegalStateException("its not possible to Marshallable and object that" +
                        " is not of type Marshallable, type=" + sb);

            ReadMarshallable m = ObjectUtils.newInstance((Class<ReadMarshallable>) clazz);

            marshallable(m, true);
            return readResolve(m);
        }

        @NotNull
        protected <T> T updateAlias() {
            throw new UnsupportedOperationException("Used by DeltaWire");
        }

        @NotNull
        protected <T> T anchor() {
            throw new UnsupportedOperationException("Used by DeltaWire");
        }

        @Nullable
        public <T> T typedMarshallable(@NotNull Function<Class, ReadMarshallable> marshallableFunction)
                throws IORuntimeException {

            int code = peekCode();
            if (code != TYPE_PREFIX)
                // todo get delta wire to support Function<Class, ReadMarshallable> correctly
                return typedMarshallable();

            @Nullable final Class aClass = typePrefix();

            if (ReadMarshallable.class.isAssignableFrom(aClass)) {
                final ReadMarshallable marshallable = marshallableFunction.apply(aClass);
                marshallable(marshallable);
                return (T) marshallable;
            }
            return (T) object(null, aClass);
        }


        @Override
        public Class typePrefix() {
            int code = peekCode();
            if (code != TYPE_PREFIX) {
                return null;
            }
            bytes.uncheckedReadSkipOne();
            @Nullable StringBuilder sb = readUtf8();

            try {
                return classLookup().forName(sb);
            } catch (ClassNotFoundException e) {
                Jvm.warn().on(BinaryWire.this.getClass(), "Unable to find class " + sb);
                return null;
            }
        }

        @NotNull
        @Override
        public <T> ValueIn typePrefix(T t, @NotNull BiConsumer<T, CharSequence> ts) {
            @NotNull StringBuilder sb = acquireStringBuilder();
            int code = readCode();
            if (code == TYPE_PREFIX) {
                bytes.readUtf8(sb);

            } else if (code == NULL) {
                sb.setLength(0);
                sb.append("!null");
            } else {
                cantRead(code);
            }
            ts.accept(t, sb);
            return this;
        }

        @NotNull
        @Override
        public <T> WireIn typeLiteralAsText(T t, @NotNull BiConsumer<T, CharSequence> classNameConsumer) {
            int code = readCode();
            if (code == TYPE_LITERAL) {
                @Nullable StringBuilder sb = readUtf8();
                classNameConsumer.accept(t, sb);
            } else if (code == NULL) {
                classNameConsumer.accept(t, null);
            } else {
                cantRead(code);
            }
            return BinaryWire.this;
        }

        @Nullable
        @Override
        public <T> Class<T> typeLiteral() {
            int code = readCode();
            if (code == TYPE_LITERAL) {
                @Nullable StringBuilder sb = readUtf8();
                try {
                    return classLookup().forName(sb);
                } catch (ClassNotFoundException e) {
                    throw new IORuntimeException(e);
                }
            } else if (code == NULL) {
                return null;

            } else {
                throw cantRead(code);
            }
        }

        @NotNull
        @Override
        public boolean marshallable(@NotNull ReadMarshallable object)
                throws BufferUnderflowException, IORuntimeException {
            return marshallable(object, true);
        }

        public boolean marshallable(@NotNull ReadMarshallable object, boolean overwrite)
                throws BufferUnderflowException, IORuntimeException {
            if (this.isNull())
                return false;
            pushState();
            long length = readLength();
            if (length >= 0) {
                long limit = bytes.readLimit();
                long limit2 = bytes.readPosition() + length;
                bytes.readLimit(limit2);
                try {
                    if (overwrite)
                        object.readMarshallable(BinaryWire.this);
                    else
                        Wires.readMarshallable(object, BinaryWire.this, false);

                } finally {
                    bytes.readLimit(limit);
                    bytes.readPosition(limit2);
                    popState();
                }
            } else {
                throw new IORuntimeException("Length unknown");
            }
            return true;
        }

        public boolean isNull() {
            consumePadding(true);
            if (peekCode() == NULL) {
                bytes.uncheckedReadSkipOne();
                return true;
            }
            return false;
        }

        @Nullable
        public Object marshallable(@Nullable Object object, @NotNull SerializationStrategy strategy)
                throws BufferUnderflowException, IORuntimeException {
            if (this.isNull())
                return null;
            pushState();
            consumePadding();
            int code = peekCode();
            switch (code) {
                case ANCHOR:
                case UPDATED_ALIAS: {
                    bytes.uncheckedReadSkipOne();
                    @NotNull Object o = code == ANCHOR ? anchor() : updateAlias();
                    if (object == null || o.getClass() != object.getClass()) {
                        return o instanceof Marshallable ? Wires.deepCopy((Marshallable) o) : o;
                    }
                    Wires.copyTo(o, object);
                    return object;
                }
            }
            long length = readLength();
            if (length >= 0) {
                long limit = bytes.readLimit();
                long limit2 = bytes.readPosition() + length;
                bytes.readLimit(limit2);
                try {
                    strategy.readUsing(object, this);

                } finally {
                    bytes.readLimit(limit);
                    bytes.readPosition(limit2);
                    popState();
                }
            } else {
                throw new IORuntimeException("Length unknown " + length);
            }
            return object;
        }

        @Nullable
        public Demarshallable demarshallable(@NotNull Class clazz) throws BufferUnderflowException, IORuntimeException {
            if (this.isNull())
                return null;

            long length = readLength();
            if (length >= 0) {
                long limit = bytes.readLimit();
                long limit2 = bytes.readPosition() + length;
                bytes.readLimit(limit2);
                try {
                    return Demarshallable.newInstance(clazz, wireIn());
                } finally {
                    bytes.readLimit(limit);
                    bytes.readPosition(limit2);
                }
            } else {
                return Demarshallable.newInstance(clazz, wireIn());
            }
        }

        @Override
        public <K extends ReadMarshallable, V extends ReadMarshallable> void typedMap(@NotNull Map<K, V> usingMap) {
            throw new UnsupportedOperationException("todo");
        }

        @NotNull
        @Override
        public <K, V> Map<K, V> map(@NotNull Class<K> kClazz, @NotNull Class<V> vClass, @NotNull Map<K, V> usingMap) {
            throw new UnsupportedOperationException("todo");
        }

        private long readTextAsLong() throws IORuntimeException, BufferUnderflowException {
            bytes.uncheckedReadSkipBackOne();
            @Nullable final String text = text();
            if (text == null)
                throw new NullPointerException();
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException e) {
                return Math.round(Double.parseDouble(text));
            }
        }

        private double readTextAsDouble() throws IORuntimeException, BufferUnderflowException {
            bytes.uncheckedReadSkipBackOne();
            @Nullable final String text = text();
            if (text == null || text.length() == 0)
                return Double.NaN;
            return Double.parseDouble(text);
        }

        @Override
        public boolean bool() throws IORuntimeException {
            consumePadding();
            int code = readCode();
            if (isText(code))
                return Boolean.valueOf(text());

            switch (code) {
                case TRUE:
                    return true;
                case FALSE:
                    return false;
            }
            throw new IORuntimeException(stringForCode(code));
        }

        @Override
        public byte int8() {
            consumePadding();
            int code = readCode();
            final long value = isText(code) ? readTextAsLong() : readInt0(code);

            if (value > Byte.MAX_VALUE || value < Byte.MIN_VALUE)
                throw new IllegalStateException();
            return (byte) value;

        }

        @Override
        public short int16() {
            consumePadding();
            int code = readCode();
            final long value = isText(code) ? readTextAsLong() : readInt0(code);
            if (value > Short.MAX_VALUE || value < Short.MIN_VALUE)
                throw new IllegalStateException();
            return (short) value;
        }

        @Override
        public int uint16() {
            consumePadding();
            int code = readCode();

            final long value = isText(code) ? readTextAsLong() : readInt0(code);

            if (value > (1L << 32L) || value < 0)
                throw new IllegalStateException("value " + value + " cannot be cast to an unsigned 16-bit int without loss of information");

            return (int) value;

        }

        @Override
        public int int32() {
            consumePadding();
            int code = readCode();
            final long value = isText(code) ? readTextAsLong() : readInt0(code);

            if (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE)
                throw new IllegalStateException("value " + value + " cannot be cast to int without loss of information");

            return (int) value;
        }

        @Override
        public long int64() {
            int code = readCode();
            if (code == PADDING || code == PADDING32 || code == COMMENT) {
                bytes.uncheckedReadSkipBackOne();
                consumePadding();
                code = readCode();
            }
            switch (code >> 4) {
                case BinaryWireHighCode.FLOAT:
                    return (long) readFloat0(code);
                case BinaryWireHighCode.INT:
                    return readInt0(code);
                default:
                    return readTextAsLong();
            }
        }

        @Override
        public double float64() {
            int code = readCode();
            if (code >> 4 == BinaryWireHighCode.FLOAT)
                return readFloat0(code);
            return isText(code) ? readTextAsDouble() : readInt0(code);
        }

        @Override
        public float float32() {
            consumePadding();
            int code = readCode();
            final double value;
            switch (code >> 16) {
                case BinaryWireHighCode.INT:
                    value = readInt0(code);
                    break;
                case BinaryWireHighCode.FLOAT:
                    value = readFloat0(code);
                    break;
                case BinaryWireHighCode.STR0:
                case BinaryWireHighCode.STR1:
                default:
                    value = readTextAsDouble();
                    break;
            }

            if (Double.isFinite(value) && (value > Float.MAX_VALUE || value < -Float.MAX_VALUE))
                throw new IllegalStateException("Cannot convert " + value + " to float");

            return (float) value;
        }

        @NotNull
        private RuntimeException cantRead(int code) {
            throw new UnsupportedOperationException(stringForCode(code));
        }

        @Override
        public Object objectWithInferredType(Object using, @NotNull SerializationStrategy strategy, Class type) {
            int code = peekCode();
            if ((code & 0x80) == 0) {
                bytes.uncheckedReadSkipOne();
                return code;
            }
            switch (code >> 4) {
                case BinaryWireHighCode.CONTROL:
                    switch (code) {
                        case BYTES_LENGTH16:
                        case BYTES_LENGTH32: {
                            if (using instanceof StringBuilder) {
                                bytesStore((StringBuilder) using);
                                return using;
                            } else if (using instanceof Bytes) {
                                bytesStore((Bytes) using);
                                return using;
                            } else {
                                long pos = bytes.readPosition();
                                bytes.uncheckedReadSkipOne();
                                int len = code == BYTES_LENGTH16 ? bytes.readUnsignedShort() : bytes.readInt();
                                code = peekCode();
                                if (code == U8_ARRAY) {
                                    bytes.readPosition(pos);
                                    return bytesStore();
                                }
                                long lim = bytes.readLimit();
                                try {
                                    bytes.readLimit(bytes.readPosition() + len);
                                    return strategy.readUsing(using, this, type);

                                } finally {
                                    bytes.readLimit(lim);
                                }
                            }
                        }
                        case U8_ARRAY: {
                            bytes.uncheckedReadSkipOne();
                            long length = bytes.readRemaining();
                            if (length == 0)
                                return BytesStore.empty();
                            @NotNull BytesStore toBytes = NativeBytesStore.lazyNativeBytesStoreWithFixedCapacity(length);
                            toBytes.write(0, bytes, bytes.readPosition(), length);
                            bytes.readSkip(length);
                            return toBytes;
                        }

                        case ANCHOR:
                        case UPDATED_ALIAS:
                            return typedMarshallable();

                    }
                    break;
                case BinaryWireHighCode.SPECIAL:
                    switch (code) {
                        case FALSE:
                            bytes.uncheckedReadSkipOne();
                            return Boolean.FALSE;
                        case TRUE:
                            bytes.uncheckedReadSkipOne();
                            return Boolean.TRUE;
                        case NULL:
                            bytes.uncheckedReadSkipOne();
                            return null;
                        case STRING_ANY:
                            return text();
                        case TYPE_PREFIX: {
                            readCode();
                            @Nullable StringBuilder sb = readUtf8();
                            final Class clazz2;
                            try {
                                clazz2 = classLookup().forName(sb);
                            } catch (ClassNotFoundException e) {
                                throw new IORuntimeException(e);
                            }
                            return object(null, clazz2);
                        }
                        case EVENT_OBJECT: {
                            if (using == null) {
                                strategy = SerializationStrategies.MAP;
                                using = strategy.newInstance(null);
                            }

                            strategy.readUsing(using, valueIn);
                            return ObjectUtils.convertTo(type, using);
                        }
                        case TIME:
                            return time();

                        case DATE:
                            return date();

                        case DATE_TIME:
                            return dateTime();

                        case ZONED_DATE_TIME:
                            return zonedDateTime();

                        case TYPE_LITERAL:
                            return typeLiteral();

                    }
                    break;

                case BinaryWireHighCode.FLOAT:
                    bytes.uncheckedReadSkipOne();
                    return readFloat0bject(code);

                case BinaryWireHighCode.INT:
                    bytes.uncheckedReadSkipOne();
                    if (code == UUID)
                        return new java.util.UUID(bytes.readLong(), bytes.readLong());
                    return readInt0object(code);
            }
            // assume it a String
            return text();
        }

        void consumeNext() {
            int code = peekCode();
            if ((code & 0x80) == 0) {
                bytes.uncheckedReadSkipOne();
                return;
            }
            switch (code >> 4) {
                case BinaryWireHighCode.CONTROL:
                    switch (code) {
                        case BYTES_LENGTH8:
                        case BYTES_LENGTH16:
                        case BYTES_LENGTH32:
                            long length = readLength();
                            bytes.readSkip(length);
                            return;
                        case ANCHOR:
                        case UPDATED_ALIAS:
                            valueIn.object();
                            return;
                        case FIELD_ANCHOR:
                            bytes.readSkip(1);
                            readFieldAnchor(acquireStringBuilder());
                            return;
                        default:
                            Jvm.warn().on(getClass(), "reading control code as text");
                    }
                    break;
                case BinaryWireHighCode.SPECIAL:
                    switch (code) {
                        case FALSE:
                        case TRUE:
                        case NULL:
                            bytes.uncheckedReadSkipOne();
                            return;
                        case STRING_ANY:
                            text();
                            return;
                        case TYPE_PREFIX: {
                            readCode();
                            @Nullable StringBuilder sb = readUtf8();
                            final Class clazz2;
                            try {
                                clazz2 = classLookup().forName(sb);
                            } catch (ClassNotFoundException e) {
                                throw new IORuntimeException(e);
                            }
                            object(null, clazz2);
                            return;
                        }
                    }
                    break;

                case BinaryWireHighCode.FLOAT:
                    bytes.uncheckedReadSkipOne();
                    readFloat0bject(code);
                    return;

                case BinaryWireHighCode.INT:
                    bytes.uncheckedReadSkipOne();
                    readInt0object(code);
                    return;
            }
            // assume it a String
            text();
        }
    }

    class DeltaValueIn extends BinaryWire.BinaryValueIn {
        @NotNull
        Marshallable[] inObjects = new Marshallable[128];
        @NotNull
        String[] inField = new String[128];

        @NotNull
        @Override
        protected <T> T anchor() {
            long ref = bytes.readStopBit();
//            System.out.println("anchor " + ref + " inObjects " + Integer.toHexString(inObjects.hashCode()));
            if (ref >= inObjects.length)
                inObjects = Arrays.copyOf(inObjects, inObjects.length * 2);
            @NotNull T t = (T) super.typedMarshallable0();
            inObjects[Maths.toUInt31(ref)] = (Marshallable) t;
            return t;
        }

        @NotNull
        @Override
        protected <T> T updateAlias() {
            int ref = Maths.toUInt31(bytes.readStopBit());
//            System.out.println("update " + ref + " inObjects " + Integer.toHexString(inObjects.hashCode()));
            Marshallable previous = inObjects[ref];
            if (previous == null)
                throw new IllegalStateException("Unknown ref: " + ref);
            super.marshallable(previous, false);
            return (T) previous;
        }

        @Override
        public int int32(int previous) {
            consumePadding();
            int code = peekCode();
            switch (code) {
                case BinaryWireCode.SET_LOW_INT8:
                    bytes.uncheckedReadSkipOne();
                    return (previous & (~0 << 8)) | bytes.readUnsignedByte();
                case BinaryWireCode.SET_LOW_INT16:
                    bytes.uncheckedReadSkipOne();
                    return (previous & (~0 << 16)) | bytes.readUnsignedShort();
                default:
                    return super.int32();
            }
        }

        @Override
        public long int64(long previous) {
            consumePadding();
            int code = peekCode();
            switch (code) {
                case BinaryWireCode.SET_LOW_INT8:
                    bytes.uncheckedReadSkipOne();
                    return (previous & (~0L << 8)) | bytes.readUnsignedByte();
                case BinaryWireCode.SET_LOW_INT16:
                    bytes.uncheckedReadSkipOne();
                    return (previous & (~0L << 16)) | bytes.readUnsignedShort();
                default:
                    return super.int64();
            }
        }

        @Override
        public float float32(float previous) {
            consumePadding();
            int code = peekCode();
            switch (code) {
                case BinaryWireCode.FLOAT_SET_LOW_2:
                    bytes.uncheckedReadSkipOne();
                    final int i = bytes.readUnsignedByte();
                    int fi = Math.round(previous * 100);
                    fi = (fi & (~0 << 8)) | i;
                    return fi / 100.0f;
                default:
                    return super.float32();
            }
        }

        @Override
        public double float64(double previous) {
            consumePadding();
            int code = peekCode();
            switch (code) {
                case BinaryWireCode.FLOAT_SET_LOW_0: {
                    bytes.uncheckedReadSkipOne();
                    final int i = bytes.readUnsignedByte();
                    long fi = Math.round(previous);
                    fi = (fi & (~0L << 8)) | i;
                    return fi;
                }
                case BinaryWireCode.FLOAT_SET_LOW_2: {
                    bytes.uncheckedReadSkipOne();
                    final int i = bytes.readUnsignedByte();
                    long fi = Math.round(previous * 100);
                    fi = (fi & (~0L << 8)) | i;
                    return fi / 100.0;
                }
                case BinaryWireCode.FLOAT_SET_LOW_4: {
                    bytes.uncheckedReadSkipOne();
                    final int i = bytes.readUnsignedByte();
                    long fi = Math.round(previous * 10000);
                    fi = (fi & (~0L << 8)) | i;
                    return fi / 1e4;
                }
                default:
                    return super.float64();
            }
        }
    }
}


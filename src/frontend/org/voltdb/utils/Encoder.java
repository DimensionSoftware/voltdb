/* This file is part of VoltDB.
 * Copyright (C) 2008-2013 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb.utils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

import com.google.common.base.Charsets;

/**
 * Encode and decode strings and byte arrays to/from hexidecimal
 * string values. This was originally added so binary values could
 * be added to the VoltDB catalogs.
 *
 */
public class Encoder {
    private static final int caseDiff = ('a' - 'A');
    /**
     *
     * @param data A binary array of bytes.
     * @return A hex-encoded string with double length.
     */
    public static String hexEncode(byte[] data) {
        if (data == null)
            return null;

        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            // hex encoding same way as java.net.URLEncoder.
            char ch = Character.forDigit((b >> 4) & 0xF, 16);
            // to uppercase
            if (Character.isLetter(ch)) {
                ch -= caseDiff;
            }
            sb.append(ch);
            ch = Character.forDigit(b & 0xF, 16);
            if (Character.isLetter(ch)) {
                ch -= caseDiff;
            }
            sb.append(ch);
        }
        return sb.toString();
    }

    /**
     *
     * @param string A (latin) string to be hex encoded.
     * @return The double-length hex encoded string.
     */
    public static String hexEncode(String string) {
        // this will need to be less "western" in the future
        return hexEncode(string.getBytes(Charsets.ISO_8859_1));
    }

    /**
     *
     * @param hexString An (even-length) hexidecimal string to be decoded.
     * @return The binary byte array value for the string (half length).
     */
    public static byte[] hexDecode(String hexString) {
        if ((hexString.length() % 2) != 0)
            throw new RuntimeException("String is not properly hex-encoded.");

        hexString = hexString.toUpperCase();
        byte[] retval = new byte[hexString.length() / 2];
        for (int i = 0; i < retval.length; i++) {
            int value = Integer.parseInt(hexString.substring(2 * i, 2 * i + 2), 16);
            if ((value < 0) || (value > 255))
                throw new RuntimeException("String is not properly hex-encoded.");
            retval[i] = (byte) value;
        }
        return retval;
    }

    /**
     *
     * @param hexString A string of hexidecimal chars of even length.
     * @return A string value de-hexed from the input.
     */
    public static String hexDecodeToString(String hexString) {
        byte[] decodedValue = hexDecode(hexString);
        return new String(decodedValue, Charsets.ISO_8859_1);
    }

    public static boolean isHexEncodedString(String hexString) {
        if ((hexString.length() % 2) != 0)
            return false;
        try {
            for (int i = 0; i < hexString.length(); i++) {
                int value = Integer.parseInt(hexString.substring(i, i + 1), 16);
                if ((value < 0) || (value > 15)) return false;
            }
        } catch (NumberFormatException e) {
            return false;
        }
        return true;
    }

    public static String compressAndBase64Encode(String string) {
        try {
            byte[] inBytes = string.getBytes(Charsets.UTF_8);
            ByteArrayOutputStream baos = new ByteArrayOutputStream((int)(string.length() * 0.7));
            DeflaterOutputStream dos = new DeflaterOutputStream(baos);
            dos.write(inBytes);
            dos.close();
            byte[] outBytes = baos.toByteArray();
            return Base64.encodeToString(outBytes, false);
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String decodeBase64AndDecompress(String string) {
        byte bytes[] = Base64.decodeFast(string);
        ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
        InflaterInputStream dis = new InflaterInputStream(bais);

        byte buffer[] = new byte[1024 * 8];
        int length = 0;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            while ( (length = dis.read( buffer )) >= 0) {
                baos.write(buffer, 0, length);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return new String(baos.toByteArray(), Charsets.UTF_8);
    }

    public static String base64Encode(String string) {
        try {
            final byte[] inBytes = string.getBytes(Charsets.UTF_8);
            return Base64.encodeToString(inBytes, false);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String base64Encode(byte[] bytes) {
        return Base64.encodeToString(bytes, false);
    }
}

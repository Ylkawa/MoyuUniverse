package com.nekoyu.Universe.AIChat.WebSearch.BiliBiliAPI;

import java.math.BigInteger;

public class abBvConvent {

    private static final BigInteger XOR_CODE = BigInteger.valueOf(23442827791579L);
    private static final BigInteger MASK_CODE = BigInteger.valueOf(2251799813685247L);
    private static final BigInteger MAX_AID = BigInteger.ONE.shiftLeft(51);
    private static final int BASE = 58;

    private static final String DATA = "FcwAPNKTMug3GV5Lj7EJnHpWsx4tb8haYeviqBz6rkCy12mUSDQX9RdoZf";

    public static String av2bv(long aidParam) {
        BigInteger aid = BigInteger.valueOf(aidParam);
        char[] bytes = {'B', 'V', '1', '0', '0', '0', '0', '0', '0', '0', '0', '0'};
        int bvIndex = bytes.length - 1;
        BigInteger tmp = MAX_AID.or(aid).xor(XOR_CODE);
        while (tmp.compareTo(BigInteger.ZERO) > 0) {
            bytes[bvIndex] = DATA.charAt(tmp.mod(BigInteger.valueOf(BASE)).intValue());
            tmp = tmp.divide(BigInteger.valueOf(BASE));
            bvIndex--;
        }
        swap(bytes, 3, 9);
        swap(bytes, 4, 7);
        return new String(bytes);
    }

    public static long bv2av(String bvid) {
        char[] bvidArr = bvid.toCharArray();
        swap(bvidArr, 3, 9);
        swap(bvidArr, 4, 7);
        String adjustedBvid = new String(bvidArr, 3, bvidArr.length - 3);
        BigInteger tmp = BigInteger.ZERO;
        for (char c : adjustedBvid.toCharArray()) {
            tmp = tmp.multiply(BigInteger.valueOf(BASE)).add(BigInteger.valueOf(DATA.indexOf(c)));
        }
        BigInteger xor = tmp.and(MASK_CODE).xor(XOR_CODE);
        return xor.longValue();
    }

    private static void swap(char[] array, int i, int j) {
        char temp = array[i];
        array[i] = array[j];
        array[j] = temp;
    }

}

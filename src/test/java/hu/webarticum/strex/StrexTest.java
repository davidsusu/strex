package hu.webarticum.strex;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.Iterator;

import org.junit.jupiter.api.Test;

class StrexTest {

    @Test
    void testEmpty() {
        Strex strex = Strex.compile("");
        check(strex, new String[] { "" });
    }

    @Test
    void testStatic() {
        Strex strex = Strex.compile("apple");
        check(strex, new String[] { "apple" });
    }

    @Test
    void testCharClazzes() {
        Strex strex = Strex.compile("[a-c][xb]");
        check(strex, new String[] { "ab", "ax", "bb", "bx", "cb", "cx" });
    }

    @Test
    void testComplexCharClazz() {
        Strex strex = Strex.compile("[3-5\\-f-hua0]");
        check(strex, new String[] { "-", "0", "3", "4", "5", "a", "f", "g", "h", "u" });
    }

    @Test
    void testCharClazzWithNonAscii() {
        Strex strex = Strex.compile("[ű2-4á]");
        check(strex, new String[] { "2", "3", "4", "á", "ű" });
    }

    @Test
    void testNegatedCharClazz() {
        Strex strex = Strex.compile("[^a-zéű]");
        check(strex, new String[] { " " }, BigInteger.valueOf(69));
    }

    @Test
    void testQuantifiers() {
        Strex strex = Strex.compile("a{3}b{2}");
        check(strex, new String[] { "aaabb" });
    }

    @Test
    void testSomeSpecials() {
        Strex strex = Strex.compile("\\t\\d");
        check(strex, new String[] { "\t0", "\t1", "\t2", "\t3", "\t4", "\t5", "\t6", "\t7", "\t8", "\t9" });
    }

    @Test
    void testOddDashes() {
        Strex strex = Strex.compile("[-xa][tr-]-");
        check(strex, new String[] { "---", "-r-", "-t-", "a--", "ar-", "at-", "x--", "xr-", "xt-" });
    }

    @Test
    void testComplex1() {
        Strex strex = Strex.compile("^^^[v-ya3]\\.\\^\\d\\W{2}\\$$");
        check(strex, new String[] { "3.^0  $" }, BigInteger.valueOf(61440));
    }

    @Test
    void testComplex2() {
        Strex strex = Strex.compile("^^[a-c\\d-]\\w\\\\\\$$$");
        check(strex, new String[] { "-_\\$" }, BigInteger.valueOf(882));
    }

    @Test
    void testComplex3() {
        Strex strex = Strex.compile("[^\\Wd-zB-Z0-9]ő{2}");
        check(strex, new String[] { "_őő", "aőő", "Aőő", "bőő", "cőő" });
    }

    @Test
    void testLarge1() {
        Strex strex = Strex.compile("\\d{70}");
        check(
                strex,
                new String[] { "0000000000000000000000000000000000000000000000000000000000000000000000" },
                new BigInteger("10000000000000000000000000000000000000000000000000000000000000000000000"));
    }

    @Test
    void testLarge2() {
        Strex strex = Strex.compile("\\w{20}\\d");
        check(
                strex,
                new String[] { "____________________0" },
                new BigInteger("9700876798663497167909692193801408010"));
    }
    
    
    void check(Strex strex, String[] outputs) {
        check(strex, outputs, BigInteger.valueOf(outputs.length));
    }

    void check(Strex strex, String[] outputs, BigInteger size) {
        assertThat(strex.size()).isEqualTo(size);
        Iterator<String> iterator = strex.iterator();
        for (int i = 0; i < outputs.length; i++) {
            assertThat(strex.get(i)).isEqualTo(outputs[i]);
            assertThat(iterator.next()).isEqualTo(outputs[i]);
        }
    }

}

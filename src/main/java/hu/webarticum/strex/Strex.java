package hu.webarticum.strex;

import java.math.BigInteger;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <p>Represents a sorted collection of strings generated from the given regular expression.</p>
 * 
 * <p>Example of use:</p>
 * 
 * <pre>
 * Strex identifiers = Strex.compile("PID:\\d{3}\\-[a-f]{5}\\-[xrbc]{3}");
 * System.out.println(identifiers.size());                       // 497664000 ( = 10^3 × 1 × 6^5 × 1 × 4^3 )
 * System.out.println(identifiers.get(0));                       // PID:000-aaaaa-bbb
 * System.out.println(identifiers.get(497663999));               // PID:999-fffff-xxx
 * System.out.println(identifiers.indexOf("PID:354-fedab-xbb")); // 176650096
 * </pre>
 */
public class Strex implements Iterable<String> {
    
    private static final Pattern QUANTIFIED_PART_PATTERN = Pattern.compile(
            "(?:(\\\\\\\\)*(\\\\)?)(\\[(?:[^\\\\\\[\\]]|\\\\.)+\\]|[^\\\\\\\\\\[\\]])\\{(\\d+)\\}"); // NOSONAR: this pattern is safe enough

    private static final Pattern START_PATTERN = Pattern.compile("^\\^*");
    
    private static final Pattern END_PATTERN = Pattern.compile("(\\\\*)\\$\\$*$");
    
    private static final Pattern ATOM_PATTERN = Pattern.compile(
            "([^\\\\\\[])|\\\\(.)|\\[(\\^)?((?:[^\\\\\\]]|\\\\.)+)\\]"); // NOSONAR: this pattern is safe enough
    
    private static final Pattern SET_ITEM_PATTERN = Pattern.compile(
            "([^\\\\])|\\\\(.)");

    
    private static final Collator collator = Collator.getInstance(Locale.US);
    
    
    private final List<char[]> parts;
    
    private final BigInteger size;

    
    private Strex(String pattern) {
        String preprocessedPattern = preprocess(pattern);
        this.parts = parse(preprocessedPattern);
        this.size = calculateSize(this.parts);
    }

    private static String preprocess(String pattern) {
        Matcher matcher = QUANTIFIED_PART_PATTERN.matcher(pattern);
        String unfolded = matcher.replaceAll(Strex::unfoldQuantifiers);
        return removeBoundariesFromStartAndEnd(unfolded);
    }
    
    private static String unfoldQuantifiers(MatchResult matchResult) {
        String unrelatedSlashes = Optional.ofNullable(matchResult.group(1)).orElse("");
        boolean slashed = matchResult.group(2) != null;
        String rawContentPart = matchResult.group(3);
        String[] contentTokens = splitContentPart(rawContentPart, slashed);
        String preContent = contentTokens[0];
        String content = contentTokens[1];
        int count = Integer.parseInt(matchResult.group(4));
        
        StringBuilder resultBuilder = new StringBuilder();
        resultBuilder.append(unrelatedSlashes);
        resultBuilder.append(preContent);
        for (int i = 0; i < count; i++) {
            resultBuilder.append(content);
        }
        return Matcher.quoteReplacement(resultBuilder.toString());
    }
    
    private static String[] splitContentPart(String rawContentPart, boolean slashed) {
        if (!slashed) {
            return new String[] { "", rawContentPart };
        } else if (rawContentPart.charAt(0) == '[') {
            int lastPos = rawContentPart.length() - 1;
            String preContent = rawContentPart.substring(0, lastPos);
            String content = rawContentPart.substring(lastPos);
            return new String[] { preContent, content };
        } else {
            return new String[] { "", "\\" + rawContentPart };
        }
    }

    private static String removeBoundariesFromStartAndEnd(String pattern) {
        String startRemoved = START_PATTERN.matcher(pattern).replaceFirst("");
        return END_PATTERN.matcher(startRemoved).replaceAll(Strex::replaceEndPattern);
    }

    private static String replaceEndPattern(MatchResult matchResult) {
        String slashes = matchResult.group(1);

        StringBuilder resultBuilder = new StringBuilder();
        resultBuilder.append(slashes);
        if (slashes.length() % 2 == 1) {
            resultBuilder.append('$');
        }
        return Matcher.quoteReplacement(resultBuilder.toString());
    }

    private static List<char[]> parse(String preprocessedPattern) {
        Matcher matcher = ATOM_PATTERN.matcher(preprocessedPattern);
        if (!matcher.replaceAll("").isEmpty()) {
            throw new IllegalArgumentException("Invalid pattern");
        }
        
        List<char[]> result = new ArrayList<>();
        matcher.reset();
        while (matcher.find()) {
            String nonEscapedContent = matcher.group(1);
            String escapedContent = matcher.group(2);
            String setContent = matcher.group(4);
            if (setContent != null) {
                boolean negated = matcher.group(3) != null;
                result.add(parseSet(setContent, negated));
            } else {
                boolean escaped = escapedContent != null;
                String content = escaped ? escapedContent : nonEscapedContent;
                result.add(parseItem(content.charAt(0), escaped));
            }
        }
        return result;
    }
    
    private static char[] parseSet(String setContent, boolean negated) {
        int length = setContent.length();
        Matcher matcher = SET_ITEM_PATTERN.matcher(setContent);
        List<char[]> unionMembers = new ArrayList<>();
        char[] previousItem = null;
        boolean inRange = false;
        boolean atStart = true;
        while (matcher.find()) {
            String nonEscapedContent = matcher.group(1);
            if (nonEscapedContent != null && nonEscapedContent.equals("-")) {
                if (atStart) {
                    previousItem = new char[] { '-' };
                } else if (matcher.end() == length && !inRange) {
                    if (previousItem != null) {
                        unionMembers.add(previousItem);
                    }
                    previousItem = new char[] { '-' };
                } else if (previousItem == null || previousItem.length != 1 || inRange) {
                    throw new IllegalArgumentException("Illegal range definition in character class");
                } else {
                    inRange = true;
                }
            } else {
                String escapedContent = matcher.group(2);
                boolean escaped = escapedContent != null;
                String content = escaped ? escapedContent : nonEscapedContent;
                char contentChar = content.charAt(0);
                char[] chars = parseItem(contentChar, escaped);
                if (inRange) {
                    if (chars.length != 1) {
                        throw new IllegalArgumentException("Illegal range definition in character class");
                    } else {
                        unionMembers.add(createCharRange(previousItem[0], chars[0]));
                        previousItem = null;
                        inRange = false;
                    }
                } else {
                    if (previousItem != null) {
                        unionMembers.add(previousItem);
                    }
                    previousItem = chars;
                }
            }
            atStart = false;
        }
        if (previousItem != null) {
            unionMembers.add(previousItem);
        }
        
        char[] positiveContent = createSortedUnion(unionMembers);
        
        return negated ? negate(positiveContent) : positiveContent;
    }

    private static char[] parseItem(char c, boolean escaped) {
        if (escaped) {
            return parseEscaped(c);
        } else {
            return parseNonEscaped(c);
        }
    }
    
    private static char[] parseNonEscaped(char c) {
        if ("?+*()".contains(Character.toString(c))) {
            throw new IllegalArgumentException("Unsupported construct: " + c);
        } else if (c == '.') {
            return new char[] {
                    ' ', '!', '"', '#', '%', '&', '\'', '(', ')', '*', '+', ',',
                    '-', '.', '/', ':', ';', '<', '=', '>', '?', '@',
                    '[', '\\', ']', '^', '_', '`', '{', '|', '}', '~', '$',
                    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
                    'a', 'A', 'b', 'B', 'c', 'C', 'd', 'D', 'e', 'E', 'f', 'F',
                    'g', 'G', 'h', 'H', 'i', 'I', 'j', 'J', 'k', 'K', 'l', 'L',
                    'm', 'M', 'n', 'N', 'o', 'O', 'p', 'P', 'q', 'Q', 'r', 'R',
                    's', 'S', 't', 'T', 'u', 'U', 'v', 'V', 'w', 'W', 'x', 'X',
                    'y', 'Y', 'z', 'Z',
            };
        } else {
            return new char[] { c };
        }
    }
    
    private static char[] parseEscaped(char c) {
        if (c == 's') {
            return new char[] { '\t', ' ' };
        } else if (c == 'S') {
            return new char[] {
                    '!', '"', '#', '%', '&', '\'', '(', ')', '*', '+', ',',
                    '-', '.', '/', ':', ';', '<', '=', '>', '?', '@',
                    '[', '\\', ']', '^', '_', '`', '{', '|', '}', '~', '$',
                    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
                    'a', 'A', 'b', 'B', 'c', 'C', 'd', 'D', 'e', 'E', 'f', 'F',
                    'g', 'G', 'h', 'H', 'i', 'I', 'j', 'J', 'k', 'K', 'l', 'L',
                    'm', 'M', 'n', 'N', 'o', 'O', 'p', 'P', 'q', 'Q', 'r', 'R',
                    's', 'S', 't', 'T', 'u', 'U', 'v', 'V', 'w', 'W', 'x', 'X',
                    'y', 'Y', 'z', 'Z',
            };
        } else if (c == 'w') {
            return new char[] {
                    '_',
                    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
                    'a', 'A', 'b', 'B', 'c', 'C', 'd', 'D', 'e', 'E', 'f', 'F',
                    'g', 'G', 'h', 'H', 'i', 'I', 'j', 'J', 'k', 'K', 'l', 'L',
                    'm', 'M', 'n', 'N', 'o', 'O', 'p', 'P', 'q', 'Q', 'r', 'R',
                    's', 'S', 't', 'T', 'u', 'U', 'v', 'V', 'w', 'W', 'x', 'X',
                    'y', 'Y', 'z', 'Z',
            };
        } else if (c == 'W') {
            return new char[] {
                    ' ', '!', '"', '#', '%', '&', '\'', '(', ')', '*', '+', ',',
                    '-', '.', '/', ':', ';', '<', '=', '>', '?', '@',
                    '[', '\\', ']', '^', '`', '{', '|', '}', '~', '$',
            };
        } else if (c == 'd') {
            return new char[] { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' };
        } else if (c == 'D') {
            return new char[] {
                    ' ', '!', '"', '#', '%', '&', '\'', '(', ')', '*', '+', ',',
                    '-', '.', '/', ':', ';', '<', '=', '>', '?', '@',
                    '[', '\\', ']', '^', '_', '`', '{', '|', '}', '~', '$',
                    'a', 'A', 'b', 'B', 'c', 'C', 'd', 'D', 'e', 'E', 'f', 'F',
                    'g', 'G', 'h', 'H', 'i', 'I', 'j', 'J', 'k', 'K', 'l', 'L',
                    'm', 'M', 'n', 'N', 'o', 'O', 'p', 'P', 'q', 'Q', 'r', 'R',
                    's', 'S', 't', 'T', 'u', 'U', 'v', 'V', 'w', 'W', 'x', 'X',
                    'y', 'Y', 'z', 'Z',
            };
        } else if (c == 't') {
            return new char[] { '\t' };
        } else if (c == 'n') {
            return new char[] { '\n' };
        } else if (c == 'r') {
            return new char[] { '\r' };
        } else {
            return new char[] { c };
        }
    }
    
    private static char[] createCharRange(char begin, char end) {
        if (end < begin) {
            throw new IllegalArgumentException("Illegal character range (end is greater)");
        }
        
        List<Character> resultBuilder = new ArrayList<>();
        for (char c = begin; c <= end; c++) {
            resultBuilder.add(c);
        }
        int size = resultBuilder.size();
        char[] result = new char[size];
        for (int i = 0; i < size; i++) {
            result[i] = resultBuilder.get(i);
        }
        return result;
    }

    private static char[] createSortedUnion(List<char[]> unionMembers) {
        Set<Character> characters = new TreeSet<>((c1, c2) -> collator.compare(Character.toString(c1), Character.toString(c2)));
        for (char[] member : unionMembers) {
            for (char c : member) {
                characters.add(c);
            }
        }
        return toArray(characters);
    }
    
    private static char[] negate(char[] positiveContent) {
        char[] dotChars = parseNonEscaped('.');
        List<Character> resultBuilder = new ArrayList<>(dotChars.length - positiveContent.length);
        for (char dotChar : dotChars) {
            boolean found = false;
            for (char c : positiveContent) {
                if (c == dotChar) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                resultBuilder.add(dotChar);
            }
        }
        return toArray(resultBuilder);
    }
    
    private static char[] toArray(Collection<Character> characterCollection) {
        int size = characterCollection.size();
        char[] result = new char[size];
        Iterator<Character> characterIterator = characterCollection.iterator();
        for (int i = 0; characterIterator.hasNext(); i++) {
            result[i] = characterIterator.next();
        }
        return result;
    }
    
    private static BigInteger calculateSize(List<char[]> parts) {
        BigInteger result = BigInteger.ONE;
        for (char[] part : parts) {
            result = result.multiply(BigInteger.valueOf(part.length));
        }
        return result;
    }


    /**
     * Compiles the given regular expression pattern, and returns with a `Strex` object
     * containing an alphabetically sorted list of all the matching strings.
     * 
     * @param pattern the regular expression used as a template
     * @return the `Strex` object containing the matching strings
     */
    public static Strex compile(String pattern) {
        return new Strex(pattern);
    }


    /**
     * Gets the size of this string collection as a `BigInteger` instance.
     * 
     * @return the size of this collection
     */
    public BigInteger size() {
        return size;
    }

    /**
     * Gets the nth generated string, in alphabetical order.
     * 
     * @param index index of the output string as a `long` value
     * @return the nth generated string
     */
    public String get(long index) {
        return get(BigInteger.valueOf(index));
    }

    /**
     * Gets the nth generated string, in alphabetical order.
     * 
     * @param index index of the output string as a `BigInteger` instance
     * @return the nth generated string
     */
    public String get(BigInteger index) {
        if (index.compareTo(BigInteger.ZERO) < 0 || index.compareTo(size) >= 0) {
            throw new ArrayIndexOutOfBoundsException("Index out of range: " + index + "(size: " + size + ")");
        }
        
        StringBuilder resultBuilder = new StringBuilder();
        BigInteger area = size;
        BigInteger position = index;
        for (char[] part : parts) {
            int count = part.length;
            area = area.divide(BigInteger.valueOf(count));
            BigInteger charIndex = position.divide(area);
            position = position.subtract(charIndex.multiply(area));
            char c = part[charIndex.intValue()];
            resultBuilder.append(c);
        }
        return resultBuilder.toString();
    }

    /**
     * Finds the given text in the output space, and provides its index,
     * or, if not found, the (-1)-based insertion point (like {@link Arrays#binarySearch(Object[], Object)}.
     * 
     * @param text the potential output to find
     * @return alphabetical index of the text or a negative number
     */
    public BigInteger indexOf(String text) {
        int patternLength = parts.size();
        int textLength = text.length();
        int length = textLength < patternLength ? textLength : patternLength;
        
        BigInteger floor = BigInteger.ZERO;
        BigInteger space = size;
        for (int i = 0; i < length; i++) {
            char[] part = parts.get(i);
            int chunkCount = part.length;
            BigInteger chunkSize = space.divide(BigInteger.valueOf(chunkCount));
            char c = text.charAt(i);
            
            int posResult = findCharInPart(part, c);
            boolean found = posResult >= 0;
            int pos = posResult >= 0 ? posResult : 0 - posResult - 1;

            floor = floor.add(chunkSize.multiply(BigInteger.valueOf(pos)));
            space = chunkSize;
            
            if (!found) {
                return floor.negate().subtract(BigInteger.ONE);
            } else if (i == length - 1) {
                if (textLength == patternLength) {
                    return floor;
                } else if (textLength < patternLength) {
                    return floor.negate().subtract(BigInteger.ONE);
                } else {
                    return floor.negate().subtract(BigInteger.TWO);
                }
            }
        }
        
        return size.negate().subtract(BigInteger.ONE);
    }
    
    private int findCharInPart(char[] part, char c) {
        String cAsString = Character.toString(c);
        for (int i = 0; i < part.length; i++) {
            char partC = part[i];
            if (partC == c) {
                return i;
            } else if (collator.compare(Character.toString(partC), cAsString) > 0) {
                return 0 - i - 1;
            }
        }
        return 0 - part.length - 1;
    }

    /**
     * Creates an iterator that iterates through the matching strings in alphabetical order.
     * 
     * @return the string iterator
     */
    @Override
    public Iterator<String> iterator() {
        return new StrexIterator();
    }
    
    
    private class StrexIterator implements Iterator<String> {
        
        BigInteger position = BigInteger.ZERO;

        @Override
        public boolean hasNext() {
            return position.compareTo(size)  < 0;
        }

        @Override
        public String next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            
            String result = get(position);
            position = position.add(BigInteger.ONE);
            return result;
        }
        
    }

}

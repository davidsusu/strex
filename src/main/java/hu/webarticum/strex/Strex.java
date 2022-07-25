package hu.webarticum.strex;

import java.math.BigInteger;
import java.text.Collator;
import java.util.ArrayList;
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

public class Strex implements Iterable<String> {
    
    private static final Pattern QUANTIFIED_PART_PATTERN = Pattern.compile(
            "(?:(\\\\\\\\)*(\\\\)?)(\\[(?:[^\\\\\\[\\]]|\\\\.)+\\]|[^\\\\\\\\\\[\\]])\\{(\\d+)\\}"); // NOSONAR: this pattern is safe enough

    private static final Pattern START_PATTERN = Pattern.compile("^\\^*");
    
    private static final Pattern END_PATTERN = Pattern.compile("(\\\\*)\\$\\$*$");
    
    private static final Pattern ATOM_PATTERN = Pattern.compile(
            "([^\\\\\\[])|\\\\(.)|\\[((?:[^\\\\\\]]|\\\\.)+)\\]"); // NOSONAR: this pattern is safe enough
    
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
            String setContent = matcher.group(3);
            if (setContent != null) {
                result.add(parseSet(setContent));
            } else {
                boolean escaped = escapedContent != null;
                String content = escaped ? escapedContent : nonEscapedContent;
                result.add(parseItem(content.charAt(0), escaped));
            }
        }
        return result;
    }
    
    private static char[] parseSet(String setContent) {
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
        return createSortedUnion(unionMembers);
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
        int size = characters.size();
        char[] result = new char[size];
        Iterator<Character> characterIterator = characters.iterator();
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

    
    public static Strex compile(String pattern) {
        return new Strex(pattern);
    }

    
    public BigInteger size() {
        return size;
    }

    public String get(long index) {
        return get(BigInteger.valueOf(index));
    }

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
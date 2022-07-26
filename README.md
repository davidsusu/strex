# Strex

**Generate sorted string list from a regular expression.**

`Strex` is a java library for generating a alphabetically sorted lazy list of
all the strings that matches to a given regular expression.

Example:

```java
Strex identifiers = Strex.compile("PID:\d{3}\-[a-f]{5}\-[xrbc]{3}");
System.out.println(identifiers.size());           // 497664000 ( = 10^3 × 1 × 6^5 × 1 × 4^3)
System.out.println(identifiers.get(0));           // PID:000-aaaaa-bbb
System.out.println(identifiers.get(497663999));   // PID:999-fffff-xxx
```

## Features

Currently a very limited subset of regular expression features is supported.
These are:

- simple literals (e. g. `a`)
- escaped literals (e. g. `\t`, `\.`)
- positive character classes (e. g. `[a-f\d]`)
- dot (`.`), generates a printable ASCII character
- fixed quantifier (e. g. `{3}`)
- optional `^` at start and `$` at end
- non-ascii characters (e. g. `ű{3}[a-záé]`)

## Planned features

In the near future it's planned to implement some more advanced constructs:

- negative character classes (e. g. `[^\da-fA-F]`)
- variable-length quantifiers (e. g. `?`, `*`, `+`, `{2,5}` etc.)
- groups (e. g. `(lorem)`, `(?<name>ipsum)`)
- alternation (e. g. `abc|xyz`)
- unicode codepoints
- and more...

## What is this good for?

There are many regex based random string generators.
Strex' main goal is different.
It provides the full list of matching strings alphabetically sorted.
This list can be very large, so `generate | sort` is not an option.

[HoloDB](https://github.com/miniconnect/holodb) uses Strex for generating random strings by a regex.
To achieve this, it selects random indices (in an invertible way), and provides the associated strings for these.
A sorted list is also an index, so we can make an efficient search in this virtual list too.

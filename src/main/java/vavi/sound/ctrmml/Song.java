/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.ctrmml;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.TreeMap;

import vavi.sound.ctrmml.platform.MdsdrvPlatform;


/**
 * Song class.
 * <p>
 * The song consists of a track map and a tag map.
 * <p>
 * The tracks represent channels or individual phrases and contain sequence data.
 * <p>
 * The tags represent song metadata (for example title and author) as well as envelopes and
 * other platform-specific data that cannot be easily represented in a portable way. Tags
 * consist of a string list and tag map keys are also strings. The {@code #} prefix is used for
 * song metadata, {@code @} for instruments or envelope data.
 * <p>
 * The {@code cmd_} prefix is special and used for platform-specific events. Use
 * {@link #registerPlatformCommand} and {@link #getPlatformCommand} to set and retrieve these
 * tags.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026-08-02 nsano initial version <br>
 * @see <a href="https://github.com/superctr/ctrmml">ctrmml</a> src/song.cpp
 */
public class Song {

    /** The tag order list key. */
    private static final String TAG_ORDER = "tag_order";

    /** {@code std::map} is ordered, and the iteration order is significant. */
    private final Map<String, List<String>> tagMap = new TreeMap<>();
    private final Map<Integer, Track> trackMap = new TreeMap<>();
    private int ppqn = 24;
    private int platformCommandIndex = -32768;
    private Platform platform = new MdsdrvPlatform(0);

    /** Get a reference to the tag map. */
    public Map<String, List<String>> getTagMap() {
        return tagMap;
    }

    /** Check if the tag with the specified key is present. */
    public boolean checkTag(String key) {
        return tagMap.containsKey(key);
    }

    /**
     * Gets the tag with the specified key.
     *
     * @throws NoSuchElementException if not found
     */
    public List<String> getTag(String key) {
        List<String> tag = tagMap.get(key);
        if (tag == null) {
            throw new NoSuchElementException(key);
        }
        return tag;
    }

    /**
     * Gets the tag with the specified key, otherwise creates a new tag.
     * <p>
     * If a new tag is created, an entry is added to the {@code tag_order} tag.
     */
    public List<String> getOrMakeTag(String key) {
        List<String> tag = tagMap.get(key);
        if (tag != null) {
            return tag;
        }
        tagMap.computeIfAbsent(TAG_ORDER, k -> new ArrayList<>()).add(key);
        return tagMap.computeIfAbsent(key, k -> new ArrayList<>());
    }

    /** Gets the tag order list. */
    public List<String> getTagOrderList() {
        return tagMap.computeIfAbsent(TAG_ORDER, k -> new ArrayList<>());
    }

    /**
     * Gets the first value of the tag with the specified key.
     *
     * @throws NoSuchElementException if not found
     */
    public String getTagFront(String key) {
        List<String> tag = getTag(key);
        if (tag.isEmpty()) {
            throw new NoSuchElementException(key);
        }
        return tag.getFirst();
    }

    /**
     * Gets the first value of the tag with the specified key. Should not throw exceptions.
     * <p>
     * If the tag is not present or empty, an empty string is returned instead.
     */
    public String getTagFrontSafe(String key) {
        List<String> tag = tagMap.get(key);
        return tag != null && !tag.isEmpty() ? tag.getFirst() : "";
    }

    /**
     * Appends a value to the tag with the specified key.
     *
     * @param key   Tag key. If the key is not found, a new tag is created.
     * @param value String that is added to the tag.
     */
    public void addTag(String key, String value) {
        getOrMakeTag(key).add(trimTrailingSpaces(value));
    }

    /**
     * Set the value to the tag with the specified key.
     * <p>
     * Overwrites all previous values that have been written to a tag and creates an item
     * containing the whole string.
     *
     * @param key   Tag key. If the key is not found, a new tag is created.
     * @param value String that is written to the tag.
     */
    public void setTag(String key, String value) {
        List<String> tag = getOrMakeTag(key);
        tag.clear();
        tag.add(trimTrailingSpaces(value));
    }

    /**
     * Append multiple values to the tag with the specified key.
     * <p>
     * {@code value} contains a string containing an array of values separated by spaces or
     * commas. Each value is added as an item in the tag. A value can be enclosed with quotes to
     * support spaces. A comma with no preceding non-space character inserts a blank item.
     *
     * @param key   Tag key. If the key is not found, a new tag is created.
     * @param value List of values to add. The list can be separated by commas OR spaces, but
     *              comma always forces a value to be added.
     */
    public void addTagList(String key, String value) {
        List<String> tag = getOrMakeTag(key);
        char[] s = value.toCharArray();
        int pos = 0;
        int lastChar = 0;
        while (pos < s.length && s[pos] != 0) {
            int next = strpbrk(s, pos, " \t\r\n\",;");
            if (next == -1) {
                // full string
                tag.add(new String(s, pos, s.length - pos));
                break;
            }
            char c = s[next];
            if (c == '\"') {
                // enclosed string (the original passes the token start, not the quote position)
                next = addTagEnclosed(tag, s, pos + 1);
                lastChar = c;
            } else {
                // separator
                String item = new String(s, pos, next - pos);
                next++;
                if (!item.isEmpty()) {
                    tag.add(item);
                    lastChar = c;
                } else if (c == ',') { // empty , block adds an empty tag
                    if (lastChar == c) {
                        tag.add("");
                    } else {
                        lastChar = c;
                    }
                }
                if (c == ';') {
                    break;
                }
            }
            pos = next;
        }
    }

    /**
     * Add a double-quote-enclosed string.
     *
     * @param pos first character of the value, not including the first {@code "}
     * @return the position just after the closing quote
     */
    private static int addTagEnclosed(List<String> tag, char[] s, int pos) {
        StringBuilder sb = new StringBuilder();
        int head = pos;
        while (head < s.length) {
            char c = s[head];
            if (c == '\\' && head + 1 < s.length) {
                head++;
                c = s[head];
                if (c == 'n') {
                    c = '\n';
                } else if (c == 't') {
                    c = '\t';
                }
            } else if (c == '"') {
                head++;
                break;
            }
            sb.append(c);
            head++;
        }
        tag.add(sb.toString());
        return head;
    }

    /** {@code strpbrk()}: index of the first character of {@code s} from {@code pos} that occurs in {@code accept}. */
    private static int strpbrk(char[] s, int pos, String accept) {
        for (int i = pos; i < s.length; i++) {
            if (accept.indexOf(s[i]) != -1) {
                return i;
            }
        }
        return -1;
    }

    private static String trimTrailingSpaces(String value) {
        int end = value.length();
        while (end > 0 && CType.isSpace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }

    /**
     * Registers a platform command.
     * <p>
     * The command can later be retrieved with {@link #getPlatformCommand}.
     *
     * @param param Command id. Set to -1 to use a sequential id.
     * @param value Command arguments.
     * @return The tag's command id.
     */
    public int registerPlatformCommand(int param, String value) {
        if (param == -1) {
            param = platformCommandIndex++;
        }
        addTagList("cmd_%d".formatted(param), value);
        return param;
    }

    /**
     * Gets the registered platform command with the specified id.
     *
     * @throws NoSuchElementException if not found
     */
    public List<String> getPlatformCommand(int param) {
        return getTag("cmd_%d".formatted(param));
    }

    /** Get a reference to the track map. */
    public Map<Integer, Track> getTrackMap() {
        return trackMap;
    }

    /**
     * Get the track with the specified id.
     *
     * @throws NoSuchElementException if not found. Use {@link #makeTrack} to create it.
     */
    public Track getTrack(int id) {
        Track track = trackMap.get(id & 0xffff);
        if (track == null) {
            throw new NoSuchElementException("track " + id);
        }
        return track;
    }

    /** Get the track with the specified id, creating it if it is not found. */
    public Track makeTrack(int id) {
        return trackMap.computeIfAbsent(id & 0xffff, k -> new Track(ppqn));
    }

    /** Gets the global Pulses per quarter note (PPQN) setting. */
    public int getPpqn() {
        return ppqn;
    }

    /** Sets the global Pulses per quarter note (PPQN) setting. */
    public void setPpqn(int newPpqn) {
        ppqn = newPpqn & 0xffff;
    }

    /** Gets the platform. */
    public Platform getPlatform() {
        return platform;
    }

    /**
     * Sets the platform.
     *
     * @return false, matching the original which never reports failure
     */
    public boolean setPlatform(String key) {
        if (CType.iequal(key, "megadrive")) {
            platform = new MdsdrvPlatform(0);
        } else if (CType.iequal(key, "mdsdrv")) {
            platform = new MdsdrvPlatform(2);
        }
        return false;
    }
}

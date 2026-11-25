package com.debianchroot.term;

import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.WcWidth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Searches visible screen + scrollback transcript lines inside a {@link TerminalEmulator}
 * and returns document-anchored coordinates for highlighting and scrolling.
 */
public final class TerminalSearch {

    public static final int MAX_MATCHES = 2000;

    public static final class Match {
        public final long absoluteRow;
        public final int externalRow;
        public final int startCol;
        public final int endCol;
        public final String lineText;

        public Match(long absoluteRow, int externalRow, int startCol, int endCol, String lineText) {
            this.absoluteRow = absoluteRow;
            this.externalRow = externalRow;
            this.startCol = startCol;
            this.endCol = endCol;
            this.lineText = lineText != null ? lineText : "";
        }
    }

    private TerminalSearch() {}

    public static List<Match> search(TerminalEmulator emulator, String query, boolean caseSensitive) {
        if (emulator == null || query == null || query.isEmpty()) {
            return Collections.emptyList();
        }
        TerminalBuffer screen = emulator.getScreen();
        if (screen == null) {
            return Collections.emptyList();
        }

        final long baseAbs = screen.getBaseAbsoluteRow();
        final int activeTranscript = screen.getActiveTranscriptRows();
        final int screenRows = emulator.mRows;
        final int cols = emulator.mColumns;
        if (cols <= 0 || screenRows <= 0) {
            return Collections.emptyList();
        }

        final String needle = caseSensitive ? query : query.toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return Collections.emptyList();
        }

        List<Match> results = new ArrayList<>();
        for (int extRow = -activeTranscript; extRow < screenRows; extRow++) {
            String line = screen.getSelectedText(0, extRow, cols - 1, extRow, false, false);
            if (line == null || line.isEmpty()) continue;

            String hay = caseSensitive ? line : line.toLowerCase(Locale.ROOT);
            int from = 0;
            while (from <= hay.length() - needle.length()) {
                int idx = hay.indexOf(needle, from);
                if (idx < 0) break;

                int startCol = charIndexToColumn(line, idx);
                int endCol = charIndexToColumn(line, Math.min(line.length(), idx + query.length()));
                if (endCol <= startCol) {
                    endCol = startCol + 1;
                }
                results.add(new Match(baseAbs + extRow, extRow, startCol, endCol, line));
                if (results.size() >= MAX_MATCHES) {
                    return results;
                }
                from = idx + Math.max(1, needle.length());
            }
        }
        return results;
    }

    private static int charIndexToColumn(String s, int targetCharIdx) {
        int col = 0;
        int i = 0;
        int limit = Math.min(targetCharIdx, s.length());
        while (i < limit) {
            int cp = Character.codePointAt(s, i);
            int w = WcWidth.width(cp);
            if (w > 0) {
                col += w;
            }
            i += Character.charCount(cp);
        }
        return col;
    }
}

import re

filepath = r"d:\JAVA\git-pro\CodeAuto\src\main\java\com\codeauto\tui\TuiApp.java"

with open(filepath, 'r', encoding='utf-8') as f:
    lines = f.readlines()

N = len(lines)
delete_set = set()

# ── helper: find the matching close-brace for a '{' at start_idx ──
def find_brace_end(start_idx):
    """start_idx is a line that contains '{'. Return exclusive end index."""
    depth = 0
    for i in range(start_idx, N):
        depth += lines[i].count('{') - lines[i].count('}')
        if depth == 0:
            return i + 1
    return None

# ── helper: find a method by its signature regex, return (start, end_exc) ──
def find_method_range(sig_regex, search_from=0):
    for i in range(search_from, N):
        if re.match(sig_regex, lines[i]):
            # find opening brace — on this line or a later one
            j = i
            while j < N and '{' not in lines[j]:
                j += 1
            if j >= N:
                return None
            end = find_brace_end(j)
            if end is None:
                return None
            return (i, end)
    return None

# ── helper: mark a range for deletion ──
def mark(start, end_exc):
    for k in range(start, end_exc):
        delete_set.add(k)

# ── helper: find and mark a block starting with a pattern ──
def find_and_mark_block(block_regex, search_from=0):
    """Find a line matching block_regex that contains or is followed by '{',
       then mark the entire brace-balanced block for deletion."""
    for i in range(search_from, N):
        if re.match(block_regex, lines[i]):
            # find the '{'
            if '{' in lines[i]:
                j = i
            else:
                j = i
                while j < N and '{' not in lines[j]:
                    j += 1
                if j >= N:
                    continue  # try next match (unlikely but safe)
            end = find_brace_end(j)
            if end is not None:
                mark(i, end)
                return i  # return start index
    return None

# ── helper: find lines matching a pattern and mark them ──
def mark_lines_matching(pattern, start_from=0):
    for i in range(start_from, N):
        if re.match(pattern, lines[i]):
            delete_set.add(i)

# =====================================================================
# 1. Remove imports (lines 16-18)
# =====================================================================
mark_lines_matching(r'^import com\.codeauto\.memory\.ActiveMemoryCaptureService;\s*$')
mark_lines_matching(r'^import com\.codeauto\.memory\.ActiveMemoryCaptureService\.MemoryCandidate;\s*$')
mark_lines_matching(r'^import com\.codeauto\.memory\.MemoryType;\s*$')

# =====================================================================
# 2. Remove field: pendingMemoryCandidates
# =====================================================================
mark_lines_matching(r'^\s*private final Deque<MemoryCandidate> pendingMemoryCandidates\b.*$')

# =====================================================================
# 3. Remove field: pendingMemoryConfirmation
# =====================================================================
mark_lines_matching(r'^\s*private volatile PendingMemoryConfirmation pendingMemoryConfirmation;\s*$')

# =====================================================================
# 4. Remove record: PendingMemoryConfirmation
# =====================================================================
mark_lines_matching(r'^\s*private record PendingMemoryConfirmation\(.*$')

# =====================================================================
# 5. In cursorBlinker: remove || pendingMemoryConfirmation != null
#    We'll do this as a text replacement later on the remaining lines.
# =====================================================================

# =====================================================================
# 6. In eventLoop: remove the if (pendingMemoryConfirmation != null) block
# =====================================================================
find_and_mark_block(r'^\s*if\s*\(\s*pendingMemoryConfirmation\s*!=\s*null\s*\)\s*\{?\s*$')

# =====================================================================
# 7. Remove methods (in dependency order, deepest/last first, to avoid
#    side-effects, but actually order doesn't matter for marking)
# =====================================================================
for sig in [
    r'^\s*private void handleMemoryConfirmationKey\(.*',
    r'^\s*private void queueMemoryCandidates\(.*',
    r'^\s*private String pendingMemorySummary\(.*',
    r'^\s*private String acceptPendingMemory\(.*',
    r'^\s*private void showNextMemoryConfirmation\(.*',
    r'^\s*private void confirmSelectedMemoryDestination\(.*',
    r'^\s*private void savePendingMemory\(.*',
    r'^\s*private void skipPendingMemory\(.*',
    r'^\s*private String saveMemoryCandidate\(.*',
    r'^\s*private static MemoryCandidate memoryCandidateFromSaveInput\(.*',
    r'^\s*private String renderMemoryConfirmationPanel\(.*',
]:
    r = find_method_range(sig)
    if r is not None:
        mark(r[0], r[1])
    else:
        print(f"WARNING: could not find method matching: {sig}")

# =====================================================================
# 8. In runMemoryCommand: remove pending/accept/skip branches
# =====================================================================
# Find the runMemoryCommand method to scope our search
rmc_range = find_method_range(r'^\s*private String runMemoryCommand\(.*')
if rmc_range is None:
    print("ERROR: could not find runMemoryCommand method")
else:
    rmc_start, rmc_end = rmc_range

    # Remove the "pending" branch: if (rest.equals("pending")) { ... }
    find_and_mark_block(r'^\s*if\s*\(\s*rest\.equals\(\"pending\"\)\s*\)\s*\{?\s*$', rmc_start)

    # Remove the "accept" branch: } else if (rest.startsWith("accept ")) { ... }
    # It starts with '} else if' so the pattern matches that
    find_and_mark_block(r'^\s*\}\s*else\s+if\s*\(\s*rest\.startsWith\(\"accept\s+\"\)\s*\)\s*\{?\s*$', rmc_start)

    # Remove the "skip" branch: } else if (rest.equals("skip")) { ... }
    find_and_mark_block(r'^\s*\}\s*else\s+if\s*\(\s*rest\.equals\(\"skip\"\)\s*\)\s*\{?\s*$', rmc_start)

    # The usage string will be updated via text replacement below.

# =====================================================================
# 9. In submitInput: remove memoryCaptureStart line and queueMemoryCandidates line
# =====================================================================
mark_lines_matching(r'^\s*int memoryCaptureStart = messages\.size\(\);\s*$')
mark_lines_matching(r'^\s*queueMemoryCandidates\(new ActiveMemoryCaptureService\(\)\.captureCandidates\(.*$')

# =====================================================================
# 10. In onToolStart: remove the save_memory interception block
# =====================================================================
find_and_mark_block(r'^\s*if\s*\(\s*\"save_memory\"\.equals\(toolName\)\s*&&.*$')

# =====================================================================
# 11. In buildBottomPanel: remove the pendingMemoryConfirmation else-if branch
#     This is } else if (pendingMemoryConfirmation != null) {
# =====================================================================
find_and_mark_block(r'^\s*\}\s*else\s+if\s*\(\s*pendingMemoryConfirmation\s*!=\s*null\s*\)\s*\{?\s*$')

# =====================================================================
# 12. Help text: remove lines with /memory pending, /memory accept, /memory skip
# =====================================================================
# We'll handle this by text replacement when processing remaining lines

# =====================================================================
# 13. Footer positioning: modify condition — text replacement below
# =====================================================================

# =====================================================================
# 14. runMemoryCommand usage string update — text replacement below
# =====================================================================

# ── Now build the output with text replacements on remaining lines ──

def apply_replacements(line):
    """Apply text replacements to a single line. Returns the modified line."""
    # 5. cursorBlinker condition
    if 'pendingMemoryConfirmation != null' in line and 'sessionPicker != null' in line:
        line = line.replace(' || pendingMemoryConfirmation != null', '')

    # 12. Help text: remove /memory pending, /memory accept, /memory skip
    if re.search(r'/memory\s+(pending|accept\s+|skip)\b', line):
        return None  # mark for deletion

    # 13. Footer positioning condition
    if 'sessionPicker == null' in line and 'pendingApproval == null' in line and 'pendingMemoryConfirmation == null' in line:
        line = line.replace(' && pendingMemoryConfirmation == null', '')

    # 14. runMemoryCommand usage string
    if '/memory list [query] | /memory pending | /memory accept' in line:
        # Replace with cleaned usage
        line = re.sub(
            r'\"Usage: /memory list \[query\] \| /memory pending \| /memory accept project\|global\|codeauto\|store \| /memory skip \| /memory add <type>::<title>::<content> \| /memory delete <id>\"',
            '"Usage: /memory list [query] | /memory add <type>::<title>::<content> | /memory delete <id>"',
            line
        )

    return line

# Build output
output_lines = []
for i, line in enumerate(lines):
    if i in delete_set:
        continue
    modified = apply_replacements(line)
    if modified is not None:
        output_lines.append(modified)

# ── Clean up: remove runs of 3+ consecutive blank/whitespace-only lines ──
cleaned = []
blank_count = 0
for line in output_lines:
    if line.strip() == '':
        blank_count += 1
        if blank_count <= 2:
            cleaned.append(line)
    else:
        blank_count = 0
        cleaned.append(line)

# ── Write back ──
with open(filepath, 'w', encoding='utf-8') as f:
    f.writelines(cleaned)

print(f"Done. Original lines: {N}, output lines: {len(cleaned)}, deleted: {len(delete_set)}")

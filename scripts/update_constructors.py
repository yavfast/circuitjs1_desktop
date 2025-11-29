#!/usr/bin/env python3
"""
Script to update CircuitElm subclass constructors to accept CircuitDocument as first parameter.
"""
import os
import re
import sys

ELEMENT_DIR = "src/main/java/com/lushprojects/circuitjs1/client/element"

# Pattern to match old 5-arg undump constructor: (int xa, int ya, int xb, int yb, int f)
# and 6-arg: (int xa, int ya, int xb, int yb, int f, StringTokenizer st)
OLD_UNDUMP_PATTERN = re.compile(
    r'(public\s+\w+Elm\s*\()\s*int\s+xa\s*,\s*int\s+ya\s*,\s*int\s+xb\s*,\s*int\s+yb\s*,\s*int\s+f\s*(,\s*\n?\s*StringTokenizer\s+\w+)?\s*\)'
)

# Pattern to match super(xa, ya, xb, yb, f) call
OLD_SUPER_UNDUMP = re.compile(
    r'super\s*\(\s*xa\s*,\s*ya\s*,\s*xb\s*,\s*yb\s*,\s*f\s*\)'
)

# Pattern for old short constructor: (int xx, int yy) without CircuitDocument
OLD_SHORT_PATTERN = re.compile(
    r'(public\s+\w+Elm\s*\()\s*int\s+xx?\s*,\s*int\s+yy?\s*\)'
)

# Pattern for super(xx, yy) or super(x, y)
OLD_SUPER_SHORT = re.compile(
    r'super\s*\(\s*xx?\s*,\s*yy?\s*\)'
)

def process_file(filepath):
    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()
    
    original = content
    
    # Update undump constructors (5/6 args -> 6/7 args with CircuitDocument)
    def replace_undump_ctor(match):
        prefix = match.group(1)  # "public XxxElm("
        st_part = match.group(2) or ""  # ", StringTokenizer st" or empty
        return f"{prefix}CircuitDocument circuitDocument, int xa, int ya, int xb, int yb, int f{st_part})"
    
    content = OLD_UNDUMP_PATTERN.sub(replace_undump_ctor, content)
    
    # Update super() calls in undump constructors
    content = OLD_SUPER_UNDUMP.sub('super(circuitDocument, xa, ya, xb, yb, f)', content)
    
    # Update short constructors (2 args -> 3 args with CircuitDocument)
    # But only if they don't already have CircuitDocument
    if 'CircuitDocument circuitDocument, int xx' not in content and 'CircuitDocument circuitDocument, int x,' not in content:
        def replace_short_ctor(match):
            prefix = match.group(1)
            # Check for xx/yy or x/y
            if 'xx' in match.group(0):
                return f"{prefix}CircuitDocument circuitDocument, int xx, int yy)"
            else:
                return f"{prefix}CircuitDocument circuitDocument, int x, int y)"
        
        content = OLD_SHORT_PATTERN.sub(replace_short_ctor, content)
        
        # Update super() calls in short constructors
        content = OLD_SUPER_SHORT.sub('super(circuitDocument, xx, yy)', content)
    
    if content != original:
        with open(filepath, 'w', encoding='utf-8') as f:
            f.write(content)
        return True
    return False

def main():
    if not os.path.exists(ELEMENT_DIR):
        print(f"Directory not found: {ELEMENT_DIR}")
        sys.exit(1)
    
    modified = 0
    for filename in os.listdir(ELEMENT_DIR):
        if filename.endswith('.java') and filename != 'CircuitElm.java' and filename != 'BaseCircuitElm.java':
            filepath = os.path.join(ELEMENT_DIR, filename)
            if process_file(filepath):
                print(f"Modified: {filename}")
                modified += 1
    
    print(f"\nTotal files modified: {modified}")

if __name__ == '__main__':
    main()

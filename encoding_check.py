import os
import sys

def check_file(path):
    with open(path, 'r', encoding='utf-8') as f:
        try:
            content = f.read()
        except UnicodeDecodeError:
            print(f"Error: {path} is not valid UTF-8.")
            return False

    mojibake_chars = ['Ã', 'Â', 'â', 'ƒ', '\xef\xbf\xbd', 'A~A'] # Note: EF BF BD is the replacement character
    for i, line in enumerate(content.splitlines()):
        for bad in mojibake_chars:
            if bad in line:
                print(f"Mojibake found in {path} at line {i+1}: contains '{bad}'")
                return False
    return True

success = True
for root, dirs, files in os.walk('app/src/main'):
    for file in files:
        if file.endswith('.java') or file.endswith('.xml'):
            if not check_file(os.path.join(root, file)):
                success = False

if not success:
    sys.exit(1)
print("Encoding sanity check passed.")

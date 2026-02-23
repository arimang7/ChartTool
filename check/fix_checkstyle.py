import xml.etree.ElementTree as ET
import re

tree = ET.parse('java/target/checkstyle-result.xml')
root = tree.getroot()

files_changed = set()
for file_elem in root.findall('file'):
    filename = file_elem.get('name')
    if not filename.endswith('.java'):
        continue
    
    with open(filename, 'r', encoding='utf-8') as f:
        lines = f.readlines()
        
    changed = False
    
    # Check trailing spaces
    for i in range(len(lines)):
        orig = lines[i]
        lines[i] = re.sub(r'[ \t]+(\r?\n)$', r'\1', lines[i])
        if orig != lines[i]:
            changed = True
            
    # Check EOF newline
    has_newline_error = any('NewlineAtEndOfFile' in err.get('source', '') for err in file_elem.findall('error'))
    if has_newline_error and lines and not lines[-1].endswith('\n'):
        lines[-1] = lines[-1] + '\n'
        changed = True
        
    if changed:
        with open(filename, 'w', encoding='utf-8') as f:
            f.writelines(lines)
        files_changed.add(filename)

print(f"Fixed formatting in {len(files_changed)} files.")

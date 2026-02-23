import xml.etree.ElementTree as ET
import sys
import os

def main():
    try:
        tree = ET.parse('java/target/checkstyle-result.xml')
        root = tree.getroot()
        count = 0
        with open('checkstyle_output.txt', 'w', encoding='utf-8') as f:
            for file_elem in root.findall('file'):
                filename = file_elem.get('name')
                short_name = os.path.basename(filename)
                errors = file_elem.findall('error')
                if errors:
                    for err in errors:
                        f.write(f"{short_name}:{err.get('line')}:{err.get('message')}\n")
    except Exception as e:
        print(f"Error: {e}")

if __name__ == '__main__':
    main()

import xml.etree.ElementTree as ET
import sys

try:
    tree = ET.parse('java/target/checkstyle-result.xml')
    root = tree.getroot()
    for file_elem in root.findall('file'):
        filename = file_elem.get('name')
        if not filename.endswith('.java'):
            continue
        short_name = filename.split('java')[-1]
        for err in file_elem.findall('error'):
            print(f"{short_name}:{err.get('line')} - {err.get('message')}")
except Exception as e:
    print(f"Error: {e}")

#!/usr/bin/env python3
from pathlib import Path
import sys,zipfile
root=Path(sys.argv[1]);app=root/sys.argv[2];output=Path(sys.argv[3])
with zipfile.ZipFile(output,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=6) as archive:
    for path in sorted(app.rglob('*')):
        archive.write(path,path.relative_to(root))
print('Portable ZIP written: '+output.name)

#!/usr/bin/env python3
from pathlib import Path
import argparse,xml.etree.ElementTree as ET,zipfile
p=argparse.ArgumentParser();p.add_argument('version',nargs='?',default='1.0.0');a=p.parse_args();root=Path(__file__).resolve().parents[1]
ns={'m':'http://maven.apache.org/POM/4.0.0'};pom=ET.parse(root/'pom.xml').getroot()
assert pom.findtext('m:groupId',namespaces=ns)=='io.github.ghosthack.harnessmanager'
assert pom.findtext('m:artifactId',namespaces=ns)=='harness-manager'
assert pom.findtext('m:url',namespaces=ns)=='https://github.com/ghosthack/harness-mgr'
assert not pom.findall('m:distributionManagement',namespaces=ns)
with zipfile.ZipFile(root/'target'/('harness-manager-'+a.version+'.jar')) as jar:
    required=['io/github/ghosthack/harnessmanager/HarnessManagerApp.class','io/github/ghosthack/harnessmanager/packaging/PackageSmoke.class','io/github/ghosthack/jmux/Main.class','io/github/ghosthack/harnessstimulator/Main.class','com/jediterm/terminal/ui/JediTermWidget.class','META-INF/LICENSE','META-INF/THIRD-PARTY.md','META-INF/licenses/JEDITERM-NOTICE.txt','META-INF/licenses/PTY4J-LICENSE']
    for name in required:assert name in jar.namelist(),name
    assert jar.read('META-INF/LICENSE')==(root/'LICENSE').read_bytes()
    assert jar.read('META-INF/THIRD-PARTY.md')==(root/'THIRD-PARTY.md').read_bytes()
    manifest=jar.read('META-INF/MANIFEST.MF').decode()
    assert 'Main-Class: io.github.ghosthack.harnessmanager.HarnessManagerApp' in manifest
    assert 'Implementation-Version: '+a.version in manifest
    assert not any(name.startswith(('org/junit/','org/opentest4j/')) for name in jar.namelist())
print('Verified Harness Manager '+a.version+': names, embedded workers, UI and license metadata')

"""Copia una lista cerrada de evidencia no secreta; nunca recorre carpetas de secretos."""
import hashlib
import json
from pathlib import Path
import shutil
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
DEST = ROOT / 'docs/entrega/evidencia'
SOURCE = ROOT / 'deploy/.demo/dfsha-demo-entrega-20261026'
NAMES = ['resultado.json', 'standby-promovido.txt', 'rabbitmq-listeners.log',
         'cliente.log', 'rf3.log', 'borrado.log', 'rechazo-sin-tls.log',
         'aislamiento-usuarios.log', 'lock-primero.log', 'lock-rechazado.log',
         'lock-expirado.log', 'carga-1.log', 'carga-2.log', 'carga-3.log', 'carga-4.log',
         'ha-subida.log', 'ha-datanode.log', 'ha-controlnode.log', 'ha-broker-borrado.log',
         'ha-postgres.log']

def digest(p): return hashlib.sha256(p.read_bytes()).hexdigest().upper()

def main():
    receipt = json.loads((SOURCE/'resultado.json').read_text(encoding='utf-8-sig'))
    assert receipt['RF3'] and receipt['BorradoFisico'] and receipt['TLSCompleto']
    assert receipt['Carga']['ChecksumsCorrectos'] == 4
    assert digest(SOURCE/'original.bin') == digest(SOURCE/'descargado.bin') == receipt['SHA256Original']
    for module, expected in receipt['JarSHA256'].items():
        assert digest(ROOT/module/'target'/f'{module}.jar') == expected, f'JAR distinto: {module}'
    reports=[]
    excluded=[]
    for p in sorted(ROOT.glob('dfsha-*/target/surefire-reports/TEST-*.xml')):
        suite=ET.parse(p).getroot()
        module=p.relative_to(ROOT).parts[0]
        source=ROOT/module/'src/test/java'/Path(*suite.get('name').split('.')).with_suffix('.java')
        if not source.is_file():
            excluded.append(p.name)
            continue  # XML historico de clases retiradas; no pertenece a esta bateria.
        row={k:int(suite.get(k,0)) for k in ['tests','failures','errors','skipped']}
        assert row['failures']==row['errors']==row['skipped']==0, p.name
        row.update(suite=suite.get('name'), xml=p.relative_to(ROOT).as_posix(), sha256=digest(p))
        reports.append(row)
    assert sum(r['tests'] for r in reports)==89
    DEST.mkdir(parents=True,exist_ok=True)
    for name in NAMES:
        data=(SOURCE/name).read_text(encoding='utf-8-sig')
        assert '-----BEGIN PRIVATE KEY' not in data and 'Bearer ey' not in data, name
        shutil.copy2(SOURCE/name,DEST/name)
    (DEST/'pruebas-maven.json').write_text(json.dumps({
        'tests':89,'failures':0,'errors':0,'skipped':0,'postgresql_real':49,
        'entorno':'JDK24/release17; proto generado por usuario, fuentes reutilizadas verificadas; package sin clean',
        'suites':reports,'xml_historicos_excluidos_sin_fuente_actual':excluded},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    manifest={p.name:digest(p) for p in sorted(DEST.iterdir()) if p.is_file() and p.name!='SHA256SUMS.json'}
    (DEST/'SHA256SUMS.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
    print(f'{len(NAMES)} archivos de demo y resumen de 89 pruebas; hashes verificados')

if __name__=='__main__': main()

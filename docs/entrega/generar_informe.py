"""Genera el PDF desde INFORME.md. Requiere reportlab; no lee secretos ni contenedores."""
from pathlib import Path
from html import escape
import re
from reportlab.pdfgen import canvas
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, PageBreak
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.colors import HexColor, white
from reportlab.lib.enums import TA_LEFT
from reportlab.graphics.shapes import Drawing, Rect, String, Line, Polygon

ROOT = Path(__file__).resolve().parent
OUT = ROOT / 'DFSha_Informe_Entrega_Local.pdf'
NAVY = HexColor('#142A42')
TEAL = HexColor('#007E87')
GRAY = HexColor('#52616B')
styles = {
    'h1': ParagraphStyle('h1', fontName='Helvetica-Bold', fontSize=38, leading=44,
                         textColor=NAVY, spaceBefore=18, spaceAfter=18),
    'h2': ParagraphStyle('h2', fontName='Helvetica-Bold', fontSize=19, leading=23,
                         textColor=NAVY, spaceAfter=14, keepWithNext=True),
    'h3': ParagraphStyle('h3', fontName='Helvetica-Bold', fontSize=12, leading=16,
                         textColor=TEAL, spaceBefore=10, spaceAfter=6, keepWithNext=True),
    'p': ParagraphStyle('p', fontName='Helvetica', fontSize=10, leading=14.2,
                        textColor=NAVY, spaceAfter=8, alignment=TA_LEFT, splitLongWords=True),
    'li': ParagraphStyle('li', fontName='Helvetica', fontSize=10, leading=14.2,
                         textColor=NAVY, spaceAfter=5, leftIndent=11, firstLineIndent=-8),
}

def architecture():
    d = Drawing(470, 202)
    def box(x, y, w, h, title, subtitle):
        d.add(Rect(x,y,w,h,rx=6,ry=6,fillColor=HexColor('#EAF4F5'),strokeColor=TEAL))
        d.add(String(x+w/2,y+h-16,title,textAnchor='middle',fontName='Helvetica-Bold',fontSize=10,fillColor=NAVY))
        d.add(String(x+w/2,y+12,subtitle,textAnchor='middle',fontSize=8,fillColor=GRAY))
    def arrow(x1,y1,x2,y2,label,tx,ty):
        d.add(Line(x1,y1,x2,y2,strokeColor=TEAL,strokeWidth=1.3))
        if y1==y2:
            sign=1 if x2>x1 else -1
            d.add(Polygon([x2,y2,x2-sign*5,y2+3,x2-sign*5,y2-3],fillColor=TEAL,strokeColor=TEAL))
        else:
            sign=1 if y2>y1 else -1
            d.add(Polygon([x2,y2,x2-3,y2-sign*5,x2+3,y2-sign*5],fillColor=TEAL,strokeColor=TEAL))
        d.add(String(tx,ty,label,fontSize=8,fillColor=GRAY))
    box(0,135,92,48,'Cliente CLI','API + instantaneas')
    box(160,135,137,48,'ControlNode 1 | 2','Metadatos / permisos')
    box(355,135,112,48,'PostgreSQL','Primario -> standby')
    box(160,35,137,48,'DataNodes 1..4','AES-GCM / copias')
    box(355,35,112,48,'RabbitMQ','Tareas / heartbeats')
    arrow(92,160,160,160,'gRPC/TLS',103,170)
    arrow(297,160,355,160,'JDBC/TLS',300,170)
    arrow(46,135,46,59,'',0,0)
    arrow(46,59,160,59,'gRPC/TLS: bytes',52,69)
    d.add(Line(228,135,228,110,strokeColor=TEAL,strokeWidth=1.3))
    d.add(Line(228,110,410,110,strokeColor=TEAL,strokeWidth=1.3))
    arrow(410,110,410,83,'AMQPS',297,118)
    arrow(355,59,297,59,'AMQPS',302,69)
    d.add(String(0,7,'Los bytes del archivo NO atraviesan ControlNode. Diagrama propio de DFSha.',fontSize=8,fillColor=GRAY))
    return d

def footer(c, doc):
    w,h = doc.pagesize
    c.setStrokeColor(TEAL); c.setLineWidth(2); c.line(48,h-35,w-48,h-35)
    c.setFont('Helvetica',8); c.setFillColor(GRAY)
    c.drawString(48,28,'DFSha | EAFIT 2026-2 | Entrega tecnica local | 26 oct 2026')
    c.drawRightString(w-48,28,str(doc.page))

def inline(text):
    text=escape(text)
    text=re.sub(r'(https://[^\s]+)',r'<link href="\1" color="#007E87">\1</link>',text)
    return text

def main():
    story=[]; pending=[]
    def flush():
        if pending:
            story.append(Paragraph(inline(' '.join(pending)),styles['p'])); pending.clear()
    for line in (ROOT/'INFORME.md').read_text(encoding='utf-8').splitlines():
        if not line.strip(): flush(); continue
        if line=='<!-- page -->': flush(); story.append(PageBreak()); continue
        if line=='<!-- architecture -->': flush(); story.append(architecture()); story.append(Spacer(1,10)); continue
        if line.startswith('#'):
            flush(); level=len(line)-len(line.lstrip('#'))
            story.append(Paragraph(inline(line[level:].strip()),styles[f'h{level}'])); continue
        if line.startswith('- ') or re.match(r'^\d+\. ',line):
            flush(); story.append(Paragraph(inline(line),styles['li'])); continue
        pending.append(line)
    flush()
    doc=SimpleDocTemplate(str(OUT),pagesize=(595.276,841.89),rightMargin=48,leftMargin=48,
                          topMargin=52,bottomMargin=47,title='DFSha - Informe de entrega local',
                          author='Camilo, Mendel y Martin',allowSplitting=1)
    doc.build(story,onFirstPage=footer,onLaterPages=footer)
    print(OUT)

if __name__=='__main__': main()

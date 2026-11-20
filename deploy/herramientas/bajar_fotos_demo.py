#!/usr/bin/env python3
"""Baja fotos reales de productos (Open Food Facts, licencia CC BY-SA) para la red demo.
Uso: python3 bajar_fotos_demo.py  -> backend/demo_fotos/<archivo>.jpg + creditos.json"""
import json, os, sys, time, urllib.parse, urllib.request

SALIDA = os.path.join(os.path.dirname(__file__), '..', '..', 'backend', 'demo_fotos')
UA = {'User-Agent': 'AnaquelDemo/1.0 (proyecto escolar)'}
# archivo: búsqueda (con marca común en México para que salga el producto típico)
BUSQUEDAS = {
    'arroz': 'arroz super extra', 'frijol': 'frijol pinto', 'aceite': 'aceite 123', 'azucar': 'azucar estandar',
    'atun': 'atun dolores', 'papel-higienico': 'papel higienico', 'harina-trigo': 'harina tres estrellas',
    'harina-maiz': 'maseca', 'sal': 'sal la fina', 'pasta': 'pasta la moderna', 'sardina': 'sardinas',
    'jalapenos': 'chiles jalapenos la costena', 'cafe': 'nescafe clasico', 'leche-polvo': 'leche nido',
    'galletas-marias': 'galletas marias', 'huevo': 'huevo blanco', 'jabon': 'jabon zote', 'detergente': 'detergente roma',
    'cloro': 'cloralex', 'servilletas': 'servilletas', 'cola-600': 'coca cola 600', 'cola-2l': 'coca cola 2 l',
    'naranja-600': 'fanta naranja', 'agua': 'agua ciel', 'jugo-naranja': 'jugo del valle naranja',
    'energizante': 'monster energy', 'papas': 'sabritas', 'cacahuate': 'cacahuates japoneses', 'palomitas': 'palomitas microondas',
    'chicles': 'chicles', 'paletas': 'paleta de caramelo', 'chocolate': 'chocolate carlos v', 'galletas-chocolate': 'chokis',
    'leche': 'leche lala entera',
}
# Palabras que el nombre del producto debe traer (y que no debe traer) para aceptarlo.
FILTRO = {
    'azucar': (['azucar', 'azúcar'], ['mascabado', 'glass', 'sustituto']), 'jalapenos': (['jalape'], ['frijol']),
    'huevo': (['huevo'], ['clara', 'líquid', 'liquid']), 'sal': (['sal'], ['sustituto', 'salsa']),
    'mantequilla': (['mantequilla'], ['margarina', 'cacahuate', 'maní']), 'leche': (['leche'], ['kirkland', 'polvo', 'deslact']),
    'queso-fresco': (['fresco'], ['cabra']), 'cola-600': (['coca'], ['light', 'zero', 'sin az']), 'cola-2l': (['coca'], ['light', 'zero', 'sin az']),
    'harina-trigo': (['harina'], ['hot cake', 'hotcake', 'pastel']), 'frijol': (['frijol'], ['charro', 'refrito', 'deshidrat']),
    'papel-higienico': (['papel'], []), 'detergente': (['detergente', 'roma', 'ariel', 'foca'], []), 'cloro': (['cloro', 'cloralex'], []),
    'servilletas': (['servillet'], []), 'chicles': (['chicle', 'goma', 'trident', 'clorets'], []),
}
EXTRA = {
    'papel-higienico': 'petalo papel', 'detergente': 'detergente foca', 'cloro': 'blanqueador cloralex',
    'servilletas': 'servilletas petalo', 'chicles': 'trident', 'azucar': 'azucar zulka', 'jalapenos': 'jalapenos en escabeche',
    'huevo': 'huevo bachoco', 'sal': 'sal de mesa', 'mantequilla': 'mantequilla lala', 'leche': 'leche entera lala',
    'cola-600': 'coca-cola', 'cola-2l': 'coca-cola original', 'harina-trigo': 'harina de trigo', 'frijol': 'frijol peruano',
}
_d = { 'leche-deslactosada': 'leche deslactosada', 'yogurt': 'yogurt bebible',
    'queso-panela': 'queso panela', 'queso-fresco': 'queso fresco', 'crema': 'crema acida', 'mantequilla': 'mantequilla',
    'jamon': 'jamon de pavo', 'salchicha': 'salchicha de pavo', 'chorizo': 'chorizo',
}
BUSQUEDAS.update(_d)
_n = {  # 2026-11-20: catálogos más completos
    'mayonesa': 'mayonesa mccormick', 'catsup': 'catsup del monte', 'frijoles-refritos': 'frijoles refritos la costena',
    'pure-tomate': 'pure de tomate del fuerte', 'consome': 'consome de pollo knorr', 'avena': 'avena quaker',
    'cereal': 'corn flakes kelloggs', 'chocolate-polvo': 'chocolate abuelita', 'mermelada': 'mermelada de fresa mccormick',
    'salsa-picante': 'salsa valentina', 'sopa-instantanea': 'maruchan', 'fecula': 'maizena', 'leche-condensada': 'la lechera',
    'leche-evaporada': 'leche evaporada carnation', 'gelatina': 'gelatina dgari', 'suavizante': 'suavitel',
    'lavatrastes': 'axion', 'shampoo': 'shampoo caprice', 'pasta-dental': 'colgate', 'jabon-tocador': 'jabon palmolive',
    'pan-blanco': 'pan blanco bimbo', 'te': 'te de manzanilla', 'vinagre': 'vinagre blanco', 'mole': 'mole dona maria',
    'agua-mineral': 'topo chico', 'refresco-limon': 'sprite', 'refresco-manzana': 'manzanita sol', 'te-helado': 'fuze tea',
    'isotonica': 'gatorade', 'jugo-mango': 'jumex mango', 'frituras-chile': 'takis', 'totopos': 'doritos nacho',
    'gomitas': 'gomitas', 'mazapan': 'mazapan de la rosa', 'pastelito': 'gansito marinela', 'queso-manchego': 'queso manchego',
    'queso-amarillo': 'queso amarillo rebanadas', 'tocino': 'tocino', 'leche-chocolate': 'leche sabor chocolate',
    'media-crema': 'media crema nestle', 'margarina': 'margarina primavera', 'yogurt-natural': 'yoghurt natural', 'bolonia': 'bologna',
}
BUSQUEDAS.update(_n)
FILTRO.update({'mayonesa': (['mayo'], []), 'catsup': (['catsup', 'ketchup'], []), 'avena': (['avena', 'oat'], ['galleta', 'barra']),
    'pasta-dental': (['colgate', 'dental', 'crema'], []), 'shampoo': (['shampoo', 'champ'], []), 'tocino': (['tocino', 'bacon'], []),
    'margarina': (['margarina', 'primavera'], []), 'gomitas': (['gomit'], []), 'pan-blanco': (['pan'], ['dulce', 'tostado']),
    'consome': (['consom', 'caldo'], []), 'vinagre': (['vinagre'], []), 'te': (['manzanilla', 'té', 'te '], []),
    'leche-chocolate': (['chocolate'], ['polvo']), 'queso-manchego': (['manchego'], []), 'queso-amarillo': (['amarillo', 'americano'], [])})
BUSQUEDAS.update(EXTRA)

def obtener(url, tiempo=40):
    for intento in range(3):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=tiempo) as r:
                return r.read()
        except Exception as e:
            print('  reintento', intento + 1, e, file=sys.stderr); time.sleep(2)
    return None

os.makedirs(SALIDA, exist_ok=True)
creditos = {}
for archivo, q in BUSQUEDAS.items():
    destino = os.path.join(SALIDA, archivo + '.jpg')
    if os.path.exists(destino):
        continue
    url = 'https://search.openfoodfacts.org/search?' + urllib.parse.urlencode({
        'q': q + ' countries_tags:"en:mexico"', 'page_size': 12, 'fields': 'code,product_name,brands,image_front_url'})
    datos = obtener(url)
    hits = json.loads(datos)['hits'] if datos else []
    si, no = FILTRO.get(archivo, ([], []))
    def vale(h):
        n = (h.get('product_name') or '').lower() + ' ' + ' '.join(h.get('brands') or []).lower()
        return h.get('image_front_url') and (not si or any(w in n for w in si)) and not any(w in n for w in no)
    elegido = next((h for h in hits if vale(h)), None)
    if not elegido:
        print('sin foto:', archivo); continue
    img = obtener(elegido['image_front_url'])  # ya viene en 400 px
    if not img:
        print('no bajó:', archivo); continue
    open(destino, 'wb').write(img)
    creditos[archivo] = {'codigo': elegido['code'], 'producto': elegido.get('product_name'), 'fuente': 'https://world.openfoodfacts.org/product/' + elegido['code']}
    print('ok', archivo, '←', elegido.get('product_name'), elegido.get('brands'), len(img) // 1024, 'KB')
    time.sleep(0.5)
ruta = os.path.join(SALIDA, 'creditos.json')
viejo = json.load(open(ruta)) if os.path.exists(ruta) else {}
viejo.update(creditos)
json.dump(viejo, open(ruta, 'w'), ensure_ascii=False, indent=1)

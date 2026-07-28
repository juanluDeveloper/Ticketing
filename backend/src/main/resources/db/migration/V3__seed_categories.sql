-- Taxonomía inicial de categorías.
--
-- Sin un árbol sembrado, la sugerencia de agrupado "por categoría + similitud de
-- nombre" (§5 del análisis) no tiene con qué trabajar durante las primeras
-- semanas, cuando aún no hay productos clasificados. Dos niveles bastan.

INSERT INTO category (code, name, parent_id) VALUES
    ('FRESCOS',        'Frescos',                NULL),
    ('DESPENSA',       'Despensa',               NULL),
    ('BEBIDAS',        'Bebidas',                NULL),
    ('REFRIGERADOS',   'Refrigerados',           NULL),
    ('CONGELADOS',     'Congelados',             NULL),
    ('CUIDADO_HOGAR',  'Cuidado del hogar',      NULL),
    ('CUIDADO_PERSONAL','Cuidado personal',      NULL),
    ('MASCOTAS',       'Mascotas',               NULL),
    ('BEBE',           'Bebé',                   NULL),
    ('OTROS',          'Otros',                  NULL);

INSERT INTO category (code, name, parent_id)
SELECT v.code, v.name, p.id
FROM (VALUES
    ('FRUTA',            'Fruta',                        'FRESCOS'),
    ('VERDURA',          'Verdura y hortalizas',         'FRESCOS'),
    ('CARNE',            'Carne',                        'FRESCOS'),
    ('PESCADO',          'Pescado y marisco',            'FRESCOS'),
    ('PANADERIA',        'Panadería y bollería',         'FRESCOS'),
    ('CHARCUTERIA',      'Charcutería',                  'FRESCOS'),

    ('ACEITE_VINAGRE',   'Aceites y vinagres',           'DESPENSA'),
    ('CONDIMENTOS',      'Especias y condimentos',       'DESPENSA'),
    ('ARROZ_PASTA',      'Arroz, pasta y legumbres',     'DESPENSA'),
    ('CONSERVAS',        'Conservas',                    'DESPENSA'),
    ('HARINA_REPOSTERIA','Harinas y repostería',         'DESPENSA'),
    ('CEREALES',         'Cereales y desayuno',          'DESPENSA'),
    ('SNACKS',           'Snacks y aperitivos',          'DESPENSA'),
    ('CHOCOLATE_DULCES', 'Chocolate y dulces',           'DESPENSA'),
    ('SALSAS',           'Salsas y untables',            'DESPENSA'),

    ('AGUA',             'Agua',                         'BEBIDAS'),
    ('REFRESCOS',        'Refrescos',                    'BEBIDAS'),
    ('ZUMOS',            'Zumos',                        'BEBIDAS'),
    ('BEBIDAS_VEGETALES','Bebidas vegetales',            'BEBIDAS'),
    ('ALCOHOL',          'Cerveza, vino y licores',      'BEBIDAS'),
    ('CAFE_INFUSIONES',  'Café e infusiones',            'BEBIDAS'),

    ('LECHE',            'Leche',                        'REFRIGERADOS'),
    ('QUESO',            'Queso',                        'REFRIGERADOS'),
    ('YOGUR_POSTRES',    'Yogures y postres',            'REFRIGERADOS'),
    ('HUEVOS',           'Huevos',                       'REFRIGERADOS'),
    ('PLATOS_PREPARADOS','Platos preparados',            'REFRIGERADOS'),

    ('CONGELADO_VERDURA','Verdura congelada',            'CONGELADOS'),
    ('CONGELADO_PESCADO','Pescado congelado',            'CONGELADOS'),
    ('CONGELADO_PRECOCINADO','Precocinados congelados',  'CONGELADOS'),
    ('HELADOS',          'Helados',                      'CONGELADOS'),

    ('DETERGENTE_ROPA',  'Detergente y suavizante',      'CUIDADO_HOGAR'),
    ('LAVAVAJILLAS',     'Lavavajillas',                 'CUIDADO_HOGAR'),
    ('LIMPIEZA_HOGAR',   'Limpieza general y lejías',    'CUIDADO_HOGAR'),
    ('PAPEL_HOGAR',      'Papel y bolsas',               'CUIDADO_HOGAR'),
    ('MENAJE',           'Menaje y utensilios',          'CUIDADO_HOGAR'),

    ('HIGIENE_CORPORAL', 'Higiene corporal',             'CUIDADO_PERSONAL'),
    ('HIGIENE_BUCAL',    'Higiene bucal',                'CUIDADO_PERSONAL'),
    ('CAPILAR',          'Cuidado capilar',              'CUIDADO_PERSONAL'),
    ('PARAFARMACIA',     'Parafarmacia',                 'CUIDADO_PERSONAL'),

    ('COMIDA_MASCOTA',   'Comida de mascota',            'MASCOTAS'),
    ('ACCESORIOS_MASCOTA','Arena y accesorios',          'MASCOTAS'),

    ('ALIMENTACION_BEBE','Alimentación infantil',        'BEBE'),
    ('HIGIENE_BEBE',     'Higiene infantil',             'BEBE')
) AS v(code, name, parent_code)
         JOIN category p ON p.code = v.parent_code;

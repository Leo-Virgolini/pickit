# Pickit

Aplicacion de escritorio JavaFX que consolida ventas de multiples canales de e-commerce y genera una lista de picking en formato Excel.

## Que hace

Recopila automaticamente las ventas pendientes de despacho desde:

- **MercadoLibre** - Ordenes con etiqueta lista para imprimir (`ready_to_print`)
- **MercadoLibre** - Ordenes con acuerdo con el vendedor (sin nota "impreso/impresa")
- **Tienda Nube KT HOGAR** - Ordenes pagadas sin enviar
- **Tienda Nube KT GASTRO** - Ordenes pagadas sin enviar
- **Productos manuales** - Agregados desde la interfaz

Luego enriquece los datos con informacion de los archivos Excel de entrada y obtiene stock en tiempo real desde las APIs, generando un Excel de picking ordenado y formateado.

## Flujo de ejecucion

1. Inicializar APIs (MercadoLibre, Tienda Nube)
2. Obtener ventas de las 4 fuentes en paralelo
3. Agregar productos manuales (si hay)
4. Consolidar y limpiar SKUs
5. Expandir combos (un combo se reemplaza por sus componentes)
6. Agrupar por SKU sumando cantidades
7. Leer datos de productos desde Stock.xlsx (descripcion, proveedor, sector, stock)
8. Ordenar por sector, proveedor, sub-rubro y descripcion
9. Generar Excel con hojas PICKIT y CARROS

## Archivos Excel de entrada

### Stock.xlsx
Datos de productos. Primera hoja del archivo.
- Columna A: SKU
- Columna B: Descripcion del producto
- Columna G: Sub-rubro
- Columna H: Stock
- Columna L: Unidad/Sector
- Columna R: Proveedor
- Datos desde fila 4

### Combos.xls
Definicion de combos (formato xls o xlsx). Primera hoja del archivo.
- Columna A: SKU del combo
- Columna C: SKU del componente
- Columna E: Cantidad del componente
- Datos desde fila 4

## Excel generado

**Archivo:** `Excel/pickit_YYYYMMdd_HHmmss.xlsx`

### Hoja PICKIT
Lista de picking consolidada.

**Columnas:** SKU | CANT | DESCRIPCION | PROVEEDOR | SECTOR | STOCK

**Formato:**
- Titulo "PICKIT KT - dd/MM/yyyy HH:mm" con fondo gris y bordes gruesos
- Calibri 14pt, centrado, bordes finos
- Filas con cantidad > 1: negrita y subrayado
- Separadores grises cuando cambia el sector
- Fondo rojo: SKU con error (sin SKU, invalido, combo invalido)
- Fondo amarillo: producto sin descripcion o sector

### Hoja CARROS
Ordenes de MercadoLibre con 2+ SKUs distintos, para preparar en carros separados.

**Columnas:** # de venta | Unidades | SKU | Producto | Sector | Carro

**Formato:**
- Titulo "CARROS KT - dd/MM/yyyy HH:mm"
- Header verde
- Fila gris por cada orden con numero de venta y letra de carro (A, B, C...)
- Filas de items con fondo gris claro
- Bordes gruesos alrededor de cada grupo de orden

## Productos manuales

Desde la interfaz se pueden agregar productos manualmente:
- Campo SKU (numerico)
- Campo Cantidad
- Boton Agregar/Modificar
- Boton Eliminar (fila seleccionada)
- Boton Borrar tabla
- No permite SKUs duplicados
- Al seleccionar una fila se puede editar

## APIs integradas

### MercadoLibre
- OAuth 2.0 con renovacion automatica de tokens
- Busqueda de ordenes por `shipping.status` y `tags`
- Ordenes "acuerdo vendedor": filtro `tags=no_shipping` + sin nota "impreso/impresa"
- Lectura de notas de ordenes

### Tienda Nube
- Soporte multi-tienda (HOGAR y GASTRO)
- Filtro: ordenes abiertas, pagadas y sin empaquetar
  - `payment_status=paid`
  - `shipping_status=unpacked`
  - `status=open`

## Manejo de errores

- Reintentos automaticos con backoff exponencial (401, 429, 5xx, errores de conexion)
- Rate limiting por API (MercadoLibre: 5 req/s, Tienda Nube: 2 req/s)
- Degradacion: si Tienda Nube no esta disponible, continua sin esos datos
- Feedback de audio (sonido de exito/error)
- Marcado visual de errores en Excel (fondo rojo/amarillo)

## Credenciales

Se almacenan en `%PROGRAMDATA%/SuperMaster/secrets/`:

| Archivo | Contenido |
|---|---|
| `ml_credentials.json` | Client ID, secret y redirect URI de MercadoLibre |
| `ml_tokens.json` | Access token y refresh token de MercadoLibre |
| `nube_tokens.json` | Credenciales por tienda de Tienda Nube |

## Tecnologias

- Java 21+
- JavaFX 21+
- Apache POI (Excel)
- Jackson (JSON)
- Google Guava (rate limiting)
- Maven

## Build

```bash
mvn clean package
```

Genera un JAR ejecutable con todas las dependencias incluidas (shaded JAR).

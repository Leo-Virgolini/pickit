# Pickit

Aplicacion de escritorio JavaFX que consolida ventas de multiples canales de e-commerce y genera una lista de picking en formato Excel.

## Que hace

Recopila automaticamente las ventas pendientes de despacho desde:

- **MercadoLibre** - Ordenes con etiqueta lista para imprimir (`ready_to_print`)
- **MercadoLibre** - Ordenes con acuerdo con el vendedor (sin nota "impreso")
- **Tienda Nube KT HOGAR** - Ordenes pagadas sin enviar
- **Tienda Nube KT GASTRO** - Ordenes pagadas sin enviar

Luego enriquece los datos con informacion del ERP **DUX** (descripcion, proveedor, stock) y genera un Excel de picking ordenado y formateado.

## Flujo de ejecucion

1. Inicializar APIs (MercadoLibre, Tienda Nube, DUX)
2. Obtener ventas de las 4 fuentes en paralelo
3. Consolidar y limpiar SKUs
4. Expandir combos (un combo se reemplaza por sus componentes)
5. Agrupar por SKU sumando cantidades
6. Enriquecer con datos de DUX (descripcion, proveedor, stock disponible)
7. Leer sectores/unidades desde el Excel PICKIT.xlsm
8. Ordenar por sector, proveedor, sub-rubro y descripcion
9. Generar Excel con formato de picking

## Excel generado

**Archivo:** `Excel/pickit_YYYYMMdd_HHmmss.xlsx`

**Columnas:** SKU | CANT | DESCRIPCION | PROVEEDOR | SECTOR | STOCK

**Formato:**
- Titulo "PICKIT KT - dd/MM/yyyy HH:mm" con fondo gris y bordes gruesos
- Calibri 14pt, centrado, bordes finos
- Filas con cantidad > 1: negrita y subrayado
- Separadores grises cuando cambia el sector

## Excel de entrada (PICKIT.xlsm)

- **Hoja COMBOS**: Define combos (col A = SKU combo, col C = SKU componente, col E = cantidad). Datos desde fila 4.
- **Hoja STOCK**: Mapeo SKU a sector/unidad (col A = SKU, col L = unidad). Datos desde fila 4.

## APIs integradas

### MercadoLibre
- OAuth 2.0 con renovacion automatica de tokens
- Busqueda de ordenes por `shipping.status` y `shipping.substatus`
- Lectura de notas de ordenes para filtrar por "impreso"

### Tienda Nube
- Soporte multi-tienda (HOGAR y GASTRO)
- Filtro: ordenes pagadas y sin enviar

### DUX ERP
- Consulta individual por codigo de producto
- Rate limit: ~7 segundos entre requests
- Obtiene: descripcion, proveedor, sub-rubro, stock disponible (suma de depositos)

## Manejo de errores

- Reintentos automaticos con backoff exponencial (401, 429, 5xx, errores de conexion)
- Rate limiting por API (MercadoLibre: 5 req/s, Tienda Nube: 2 req/s, DUX: 0.143 req/s)
- Degradacion: si DUX o Tienda Nube no estan disponibles, continua sin esos datos
- Feedback de audio (sonido de exito/error)

## Credenciales

Se almacenan en `%PROGRAMDATA%/SuperMaster/secrets/`:

| Archivo | Contenido |
|---|---|
| `ml_credentials.json` | Client ID, secret y redirect URI de MercadoLibre |
| `ml_tokens.json` | Access token y refresh token de MercadoLibre |
| `nube_tokens.json` | Credenciales por tienda de Tienda Nube |
| `dux_tokens.json` | Token bearer de DUX |

## Tecnologias

- Java 25
- JavaFX 25
- Apache POI 5.5.1 (Excel)
- Jackson 3.0.4 (JSON)
- Google Guava 33.5.0 (rate limiting)
- Maven

## Build

```bash
mvn clean package
```

Genera un JAR ejecutable con todas las dependencias incluidas (shaded JAR).

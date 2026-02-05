package ar.com.leo.pickit.model;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

public class OrdenML {

    private final long orderId;
    private final Long packId;
    private final OffsetDateTime fechaCreacion;
    private final List<Venta> items;

    public OrdenML(long orderId, Long packId, OffsetDateTime fechaCreacion) {
        this.orderId = orderId;
        this.packId = packId;
        this.fechaCreacion = fechaCreacion;
        this.items = new ArrayList<>();
    }

    public long getOrderId() {
        return orderId;
    }

    public Long getPackId() {
        return packId;
    }

    public OffsetDateTime getFechaCreacion() {
        return fechaCreacion;
    }

    public List<Venta> getItems() {
        return items;
    }

    /**
     * ID que agrupa la venta: pack_id si existe, sino order_id.
     */
    public long getVentaId() {
        return packId != null ? packId : orderId;
    }

    /**
     * Retorna los ultimos 5 digitos del ventaId como numero de venta.
     */
    public String getNumeroVenta() {
        String id = String.valueOf(getVentaId());
        return id.length() > 5 ? id.substring(id.length() - 5) : id;
    }
}

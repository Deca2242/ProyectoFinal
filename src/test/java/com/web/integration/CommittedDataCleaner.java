package com.web.integration;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collection;
import java.util.List;
import java.util.Map;

// Borra lo que confirma (commit) una prueba sin transacción de prueba. Antes de cada fila borra las que la
// referencian por una FK sin ON DELETE (tickets de un viaje, equipaje de un ticket...), consultando el catálogo
// de PostgreSQL: así sigue funcionando aunque se agreguen tablas nuevas que referencien a las existentes.
// Las FK con ON DELETE CASCADE / SET NULL las resuelve la propia base de datos (p. ej. config.updated_by)
final class CommittedDataCleaner {

    private final JdbcTemplate jdbc;

    CommittedDataCleaner(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void delete(String table, Collection<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        Long[] idArray = ids.toArray(new Long[0]);
        List<Map<String, Object>> references = jdbc.queryForList("""
                SELECT child.relname AS child_table, att.attname AS child_column
                FROM pg_constraint con
                JOIN pg_class child ON child.oid = con.conrelid
                JOIN pg_class parent ON parent.oid = con.confrelid
                JOIN pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = con.conkey[1]
                WHERE con.contype = 'f'
                AND parent.relname = ?
                AND con.confdeltype IN ('a', 'r')
                """, table);
        for (Map<String, Object> reference : references) {
            String childTable = (String) reference.get("child_table");
            String childColumn = (String) reference.get("child_column");
            if (hasIdColumn(childTable)) {
                List<Long> childIds = jdbc.queryForList(
                        "SELECT id FROM " + childTable + " WHERE " + childColumn + " = ANY(?)", Long.class, (Object) idArray);
                delete(childTable, childIds);
            } else {
                jdbc.update("DELETE FROM " + childTable + " WHERE " + childColumn + " = ANY(?)", (Object) idArray);
            }
        }
        jdbc.update("DELETE FROM " + table + " WHERE id = ANY(?)", (Object) idArray);
    }

    private boolean hasIdColumn(String table) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = ? AND column_name = 'id'
                """, Integer.class, table);
        return count != null && count > 0;
    }
}

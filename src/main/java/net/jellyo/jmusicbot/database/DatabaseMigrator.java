/*
 * Copyright 2026 Jellyo.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jagrosh.jmusicbot.database;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One-time, idempotent merge of the bot's legacy split SQLite databases into the
 * single unified {@link Database} file.
 *
 * <p>For each legacy source file the migrator copies every user table into the
 * unified database, creating the table from the legacy schema if it does not
 * already exist and otherwise copying only the columns the two schemas share
 * (so older/newer schema variants merge cleanly). All copies use
 * {@code INSERT OR IGNORE}, and a {@code schema_migrations} bookkeeping table
 * records which sources have been imported, so running the migrator repeatedly
 * is a no-op. Failures are logged but never thrown to the caller — a migration
 * problem must not stop the bot from starting.
 */
public final class DatabaseMigrator
{
    private static final Logger LOG = LoggerFactory.getLogger(DatabaseMigrator.class);
    private static final String ATTACH_ALIAS = "legacy_src";
    private static final String MIGRATION_TABLE = "schema_migrations";

    private DatabaseMigrator() {}

    /** A legacy database file to merge, paired with a stable logical id. */
    public static final class LegacySource
    {
        private final String id;
        private final Path path;

        public LegacySource(String id, Path path)
        {
            this.id = id;
            this.path = path;
        }

        public String getId() { return id; }
        public Path getPath() { return path; }
    }

    /**
     * Merges the given legacy sources into the unified database. Idempotent and
     * exception-safe; problems are logged.
     *
     * @param unifiedPath the unified database file
     * @param sources legacy database files to import
     */
    public static void merge(Path unifiedPath, List<LegacySource> sources)
    {
        if(sources == null || sources.isEmpty())
            return;
        try(Connection connection = Database.open(unifiedPath))
        {
            ensureMigrationTable(connection);
            Path unifiedAbs = unifiedPath.toAbsolutePath().normalize();
            for(LegacySource source : sources)
            {
                try
                {
                    mergeSource(connection, unifiedAbs, source);
                }
                catch(Exception ex)
                {
                    LOG.warn("Failed to merge legacy database '{}' ({}) into {}", source.getId(),
                            source.getPath(), unifiedAbs, ex);
                    rollbackQuietly(connection);
                    detachQuietly(connection);
                    setAutoCommitQuietly(connection);
                }
            }
        }
        catch(SQLException ex)
        {
            LOG.warn("Could not open unified database for migration at {}", unifiedPath.toAbsolutePath(), ex);
        }
    }

    private static void mergeSource(Connection connection, Path unifiedAbs, LegacySource source) throws SQLException
    {
        String migrationId = "legacy-merge:" + source.getId();
        if(isApplied(connection, migrationId))
            return;

        Path legacy = source.getPath();
        if(legacy != null && legacy.toAbsolutePath().normalize().equals(unifiedAbs))
        {
            // The source IS the unified database; there is genuinely nothing to ever import.
            markApplied(connection, migrationId);
            return;
        }
        if(legacy == null || !Files.exists(legacy))
        {
            // Not present (yet). Do NOT mark applied — a legacy file restored from a backup
            // after the first boot must still be imported on a later start, not skipped forever.
            LOG.debug("Legacy source '{}' not present at {}; will retry on next start", source.getId(), legacy);
            return;
        }

        LOG.info("Merging legacy database '{}' from {} into unified database", source.getId(), legacy.toAbsolutePath());

        // PRAGMA foreign_keys and ATTACH must run outside an open transaction.
        boolean priorForeignKeys = queryForeignKeys(connection);
        setForeignKeys(connection, false);
        attach(connection, legacy.toAbsolutePath().toString());
        int copied = 0;
        int failed = 0;
        int droppedRows = 0;
        try
        {
            connection.setAutoCommit(false);
            List<String> tables = listTables(connection);
            for(String table : tables)
            {
                try
                {
                    int dropped = copyTable(connection, table);
                    if(dropped >= 0)
                    {
                        copied++;
                        droppedRows += dropped;
                    }
                }
                catch(SQLException ex)
                {
                    // One unmappable table must not abort the rest of the import.
                    failed++;
                    LOG.warn("Skipping table '{}' from legacy database '{}': {}", table, source.getId(), ex.getMessage());
                }
            }
            recreateIndexes(connection);
            markApplied(connection, migrationId);
            connection.commit();
        }
        catch(SQLException ex)
        {
            connection.rollback();
            throw ex;
        }
        finally
        {
            connection.setAutoCommit(true);
            detachQuietly(connection);
            setForeignKeys(connection, priorForeignKeys);
        }

        LOG.info("Merged {} table(s) from legacy database '{}'", copied, source.getId());
        if(failed == 0 && droppedRows == 0)
            renameMigrated(legacy);
        else
            // Never delete a source we did not fully absorb: leave it in place so the
            // un-merged rows remain recoverable by the operator.
            LOG.warn("Left legacy database '{}' in place ({} table(s) could not be merged, {} row(s) skipped on key collision)",
                    source.getId(), failed, droppedRows);
    }

    private static void ensureMigrationTable(Connection connection) throws SQLException
    {
        try(Statement st = connection.createStatement())
        {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS " + MIGRATION_TABLE + " ("
                    + "migration_id TEXT PRIMARY KEY,"
                    + "applied_at INTEGER NOT NULL"
                    + ")");
        }
    }

    private static boolean isApplied(Connection connection, String migrationId) throws SQLException
    {
        try(PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM " + MIGRATION_TABLE + " WHERE migration_id=?"))
        {
            ps.setString(1, migrationId);
            try(ResultSet rs = ps.executeQuery())
            {
                return rs.next();
            }
        }
    }

    private static void markApplied(Connection connection, String migrationId) throws SQLException
    {
        try(PreparedStatement ps = connection.prepareStatement(
                "INSERT OR IGNORE INTO " + MIGRATION_TABLE + "(migration_id, applied_at) VALUES(?, ?)"))
        {
            ps.setString(1, migrationId);
            ps.setLong(2, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    private static void attach(Connection connection, String path) throws SQLException
    {
        try(PreparedStatement ps = connection.prepareStatement("ATTACH DATABASE ? AS " + ATTACH_ALIAS))
        {
            ps.setString(1, path);
            ps.execute();
        }
    }

    private static List<String> listTables(Connection connection) throws SQLException
    {
        List<String> tables = new ArrayList<>();
        try(Statement st = connection.createStatement();
            ResultSet rs = st.executeQuery("SELECT name FROM " + ATTACH_ALIAS + ".sqlite_master "
                    + "WHERE type='table' AND name NOT LIKE 'sqlite_%'"))
        {
            while(rs.next())
            {
                String name = rs.getString(1);
                if(name != null && !MIGRATION_TABLE.equalsIgnoreCase(name))
                    tables.add(name);
            }
        }
        return tables;
    }

    /**
     * Copies a legacy table into the unified database with {@code INSERT OR IGNORE}.
     *
     * @return the number of legacy rows that were skipped because they collided with
     *         existing keys in the unified table (0 = fully merged), or -1 if the table
     *         was skipped entirely (no shared columns / no schema to create from).
     */
    private static int copyTable(Connection connection, String table) throws SQLException
    {
        if(!tableExists(connection, "main", table))
        {
            String createSql = tableCreateSql(connection, table);
            if(createSql == null)
                return -1;
            try(Statement st = connection.createStatement())
            {
                st.executeUpdate(createSql);
            }
        }

        List<String> common = commonColumns(connection, table);
        if(common.isEmpty())
            return -1;

        StringBuilder cols = new StringBuilder();
        for(String c : common)
        {
            if(cols.length() > 0)
                cols.append(", ");
            cols.append('"').append(c).append('"');
        }
        long sourceRows = countRows(connection, ATTACH_ALIAS, table);
        String sql = "INSERT OR IGNORE INTO main.\"" + table + "\" (" + cols + ") "
                + "SELECT " + cols + " FROM " + ATTACH_ALIAS + ".\"" + table + "\"";
        int inserted;
        try(Statement st = connection.createStatement())
        {
            inserted = st.executeUpdate(sql);
        }
        long dropped = Math.max(0L, sourceRows - inserted);
        if(dropped > 0)
            LOG.warn("Table '{}': {} of {} legacy row(s) skipped due to key collisions with existing unified data",
                    table, dropped, sourceRows);
        return (int) Math.min(Integer.MAX_VALUE, dropped);
    }

    private static long countRows(Connection connection, String schema, String table) throws SQLException
    {
        try(Statement st = connection.createStatement();
            ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + schema + ".\"" + table + "\""))
        {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    private static void recreateIndexes(Connection connection) throws SQLException
    {
        List<String> ddls = new ArrayList<>();
        try(Statement st = connection.createStatement();
            ResultSet rs = st.executeQuery("SELECT sql FROM " + ATTACH_ALIAS + ".sqlite_master "
                    + "WHERE type='index' AND sql IS NOT NULL"))
        {
            while(rs.next())
            {
                String sql = rs.getString(1);
                if(sql != null && !sql.isBlank())
                    ddls.add(injectIfNotExists(sql));
            }
        }
        for(String ddl : ddls)
        {
            try(Statement st = connection.createStatement())
            {
                st.executeUpdate(ddl);
            }
            catch(SQLException ex)
            {
                LOG.debug("Skipped legacy index recreation: {}", ex.getMessage());
            }
        }
    }

    private static List<String> commonColumns(Connection connection, String table) throws SQLException
    {
        List<String> mainCols = columns(connection, "main", table);
        Set<String> legacyCols = new LinkedHashSet<>();
        for(String c : columns(connection, ATTACH_ALIAS, table))
            legacyCols.add(c.toLowerCase(Locale.ROOT));
        List<String> common = new ArrayList<>();
        for(String c : mainCols)
            if(legacyCols.contains(c.toLowerCase(Locale.ROOT)))
                common.add(c);
        return common;
    }

    private static List<String> columns(Connection connection, String schema, String table) throws SQLException
    {
        List<String> cols = new ArrayList<>();
        try(Statement st = connection.createStatement();
            ResultSet rs = st.executeQuery("PRAGMA " + schema + ".table_info(\"" + table + "\")"))
        {
            while(rs.next())
                cols.add(rs.getString("name"));
        }
        return cols;
    }

    private static boolean tableExists(Connection connection, String schema, String table) throws SQLException
    {
        try(PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM " + schema + ".sqlite_master WHERE type='table' AND name=?"))
        {
            ps.setString(1, table);
            try(ResultSet rs = ps.executeQuery())
            {
                return rs.next();
            }
        }
    }

    private static String tableCreateSql(Connection connection, String table) throws SQLException
    {
        try(PreparedStatement ps = connection.prepareStatement(
                "SELECT sql FROM " + ATTACH_ALIAS + ".sqlite_master WHERE type='table' AND name=?"))
        {
            ps.setString(1, table);
            try(ResultSet rs = ps.executeQuery())
            {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static String injectIfNotExists(String sql)
    {
        String trimmed = sql.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if(lower.contains("if not exists"))
            return trimmed;
        if(lower.startsWith("create unique index "))
            return "CREATE UNIQUE INDEX IF NOT EXISTS " + trimmed.substring("create unique index ".length());
        if(lower.startsWith("create index "))
            return "CREATE INDEX IF NOT EXISTS " + trimmed.substring("create index ".length());
        return trimmed;
    }

    private static boolean queryForeignKeys(Connection connection)
    {
        try(Statement st = connection.createStatement();
            ResultSet rs = st.executeQuery("PRAGMA foreign_keys"))
        {
            return rs.next() && rs.getInt(1) == 1;
        }
        catch(SQLException ex)
        {
            return true;
        }
    }

    private static void setForeignKeys(Connection connection, boolean enabled)
    {
        try(Statement st = connection.createStatement())
        {
            st.execute("PRAGMA foreign_keys=" + (enabled ? "ON" : "OFF"));
        }
        catch(SQLException ex)
        {
            LOG.debug("Failed to toggle foreign_keys pragma during migration", ex);
        }
    }

    private static void detachQuietly(Connection connection)
    {
        try(Statement st = connection.createStatement())
        {
            st.execute("DETACH DATABASE " + ATTACH_ALIAS);
        }
        catch(SQLException ex)
        {
            LOG.debug("Failed to detach legacy database (may already be detached): {}", ex.getMessage());
        }
    }

    private static void rollbackQuietly(Connection connection)
    {
        try
        {
            if(!connection.getAutoCommit())
                connection.rollback();
        }
        catch(SQLException ex)
        {
            LOG.debug("Failed to roll back migration transaction", ex);
        }
    }

    private static void setAutoCommitQuietly(Connection connection)
    {
        try
        {
            connection.setAutoCommit(true);
        }
        catch(SQLException ex)
        {
            LOG.debug("Failed to restore auto-commit after migration failure", ex);
        }
    }

    private static void renameMigrated(Path legacy)
    {
        Path target = legacy.resolveSibling(legacy.getFileName().toString() + ".migrated");
        try
        {
            Files.move(legacy, target, StandardCopyOption.REPLACE_EXISTING);
            LOG.info("Renamed merged legacy database {} -> {}", legacy.getFileName(), target.getFileName());
            deleteQuietly(legacy.resolveSibling(legacy.getFileName().toString() + "-wal"));
            deleteQuietly(legacy.resolveSibling(legacy.getFileName().toString() + "-shm"));
        }
        catch(Exception ex)
        {
            LOG.debug("Could not rename merged legacy database {} (left in place)", legacy, ex);
        }
    }

    private static void deleteQuietly(Path path)
    {
        try
        {
            Files.deleteIfExists(path);
        }
        catch(Exception ignored) {}
    }
}

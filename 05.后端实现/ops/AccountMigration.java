import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** inspect 只读；apply 先在仓库外备份账号表，再做两项条件式迁移。 */
class AccountMigration {
    private static final String TABLE = "sa_sales_rep";

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || (!"inspect".equals(args[0]) && !"apply".equals(args[0])))
            throw new IllegalArgumentException("用法：AccountMigration inspect|apply");
        Map<String, String> env = new HashMap<>();
        for (String raw : Files.readAllLines(Path.of(".env"), StandardCharsets.UTF_8)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) continue;
            int i = line.indexOf('=');
            String value = line.substring(i + 1).trim();
            if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'")))) value = value.substring(1, value.length() - 1);
            env.put(line.substring(0, i).trim(), value);
        }
        String host = required(env, "DB_HOST"), port = required(env, "DB_PORT");
        String database = required(env, "DB_NAME"), username = required(env, "DB_USERNAME");
        String password = required(env, "DB_PASSWORD");
        if (!database.matches("[A-Za-z0-9_-]+") || !port.matches("[0-9]+"))
            throw new IllegalArgumentException("数据库名称或端口格式错误");
        String url = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=false&serverTimezone=Asia/Shanghai&characterEncoding=utf8"
                + "&allowPublicKeyRetrieval=true&connectTimeout=5000&socketTimeout=30000";
        try (Connection db = DriverManager.getConnection(url, username, password)) {
            inspect(db);
            if ("apply".equals(args[0])) {
                Path backup = backup(db);
                System.out.println("备份文件：" + backup);
                migrate(db);
                inspect(db);
            }
        }
    }

    private static String required(Map<String, String> env, String key) {
        String value = System.getenv().getOrDefault(key, env.get(key));
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " 未配置");
        return value;
    }

    private static boolean hasColumn(Connection db, String name) throws Exception {
        try (ResultSet columns = db.getMetaData().getColumns(db.getCatalog(), null, TABLE, name)) {
            return columns.next();
        }
    }

    private static boolean nullableRegion(Connection db) throws Exception {
        try (ResultSet columns = db.getMetaData().getColumns(db.getCatalog(), null, TABLE, "region_id")) {
            if (!columns.next()) throw new IllegalStateException("目标库不存在账号表 region_id 列");
            return columns.getInt("NULLABLE") == DatabaseMetaData.columnNullable;
        }
    }

    private static long count(Connection db) throws Exception {
        try (Statement stmt = db.createStatement(); ResultSet rows = stmt.executeQuery("SELECT COUNT(*) FROM " + TABLE)) {
            rows.next(); return rows.getLong(1);
        }
    }

    private static void inspect(Connection db) throws Exception {
        System.out.printf("目标库=%s，账号行数=%d，active列=%s，region_id可空=%s%n",
                db.getCatalog(), count(db), hasColumn(db, "active"), nullableRegion(db));
    }

    private static Path backup(Connection db) throws Exception {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "sales-agent-backups");
        Files.createDirectories(dir);
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC).format(Instant.now());
        Path file = dir.resolve(TABLE + "-" + stamp + ".sql");
        long expected = count(db), written = 0;
        try (var out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
             Statement stmt = db.createStatement()) {
            try (ResultSet ddl = stmt.executeQuery("SHOW CREATE TABLE " + TABLE)) {
                ddl.next(); out.write(ddl.getString(2) + ";\n");
            }
            try (ResultSet rows = stmt.executeQuery("SELECT * FROM " + TABLE + " ORDER BY id")) {
                ResultSetMetaData fields = rows.getMetaData();
                while (rows.next()) {
                    StringBuilder sql = new StringBuilder("INSERT INTO " + TABLE + " (");
                    for (int i = 1; i <= fields.getColumnCount(); i++) {
                        if (i > 1) sql.append(',');
                        sql.append('`').append(fields.getColumnName(i)).append('`');
                    }
                    sql.append(") VALUES (");
                    for (int i = 1; i <= fields.getColumnCount(); i++) {
                        if (i > 1) sql.append(',');
                        Object value = rows.getObject(i);
                        if (value == null) sql.append("NULL");
                        else if (value instanceof Number || value instanceof Boolean) sql.append(value);
                        else sql.append('\'').append(escape(value.toString())).append('\'');
                    }
                    out.write(sql.append(");\n").toString()); written++;
                }
            }
        }
        if (written != expected || Files.size(file) == 0) throw new IllegalStateException("备份行数校验失败，拒绝迁移");
        byte[] sha = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
        System.out.printf("备份校验：%d 行，SHA-256=%s%n", written, HexFormat.of().formatHex(sha));
        return file;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\u0000", "\\0").replace("\n", "\\n")
                .replace("\r", "\\r").replace("'", "''").replace("\u001a", "\\Z");
    }

    private static void migrate(Connection db) throws Exception {
        try (Statement stmt = db.createStatement()) {
            if (!hasColumn(db, "active")) {
                stmt.executeUpdate("ALTER TABLE " + TABLE
                        + " ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE COMMENT '账号是否启用' AFTER password");
                System.out.println("已添加 active 列");
            }
            if (!nullableRegion(db)) {
                stmt.executeUpdate("ALTER TABLE " + TABLE
                        + " MODIFY COLUMN region_id BIGINT NULL COMMENT '所属大区；系统管理员为空'");
                System.out.println("已允许 region_id 为空");
            }
        }
    }
}

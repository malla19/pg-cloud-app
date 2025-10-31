import static spark.Spark.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.sql.*;
import java.util.*;

public class App {
    // --- Simple helper to read DB config from env ---
    static class Db {
        static String jdbcUrl;
        static Properties props = new Properties();

        static void initFromEnv() throws Exception {
            // Prefer DATABASE_URL (Render/Railway/Heroku style)
            String dbUrl = System.getenv("DATABASE_URL"); // e.g. postgres://user:pass@host:5432/db
            if (dbUrl != null && dbUrl.startsWith("postgres://")) {
                URI uri = new URI(dbUrl);
                String[] ui = uri.getUserInfo().split(":");
                String user = ui[0];
                String pass = ui[1];
                String host = uri.getHost();
                int port = (uri.getPort() == -1) ? 5432 : uri.getPort();
                String db = uri.getPath().replaceFirst("/", "");
                // Many cloud PG require SSL
                jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + db + "?sslmode=require";
                props.setProperty("user", user);
                props.setProperty("password", pass);
            } else {
                // Fallback to individual vars for local dev
                String host = getOr("DB_HOST", "localhost");
                String port = getOr("DB_PORT", "5432");
                String name = getOr("DB_NAME", "testdb");
                String user = getOr("DB_USER", "postgres");
                String pass = getOr("DB_PASS", "postgres");
                jdbcUrl = "jdbc:postgresql://" + host + ":" + port + "/" + name;
                props.setProperty("user", user);
                props.setProperty("password", pass);
            }
        }

        static Connection conn() throws Exception {
            if (jdbcUrl == null) initFromEnv();
            return DriverManager.getConnection(jdbcUrl, props);
        }

        static String getOr(String k, String d) {
            String v = System.getenv(k);
            return (v == null || v.isBlank()) ? d : v;
        }
    }

    public static void main(String[] args) {
        port(getPort());                 // Render injects PORT
        staticFiles.location("/public"); // Optional: simple UI later
        ObjectMapper om = new ObjectMapper();

        // Health check
        get("/health", (req, res) -> {
            res.type("application/json");
            return "{\"status\":\"OK\"}";
        });

        // Initialize table
        post("/init", (req, res) -> {
            try (Connection c = Db.conn(); Statement st = c.createStatement()) {
                st.execute("""
                   CREATE TABLE IF NOT EXISTS cities(
                     id SERIAL PRIMARY KEY,
                     name TEXT NOT NULL,
                     country TEXT NOT NULL,
                     created_at TIMESTAMPTZ DEFAULT NOW()
                   )
                """);
                res.type("application/json");
                return "{\"ok\":true,\"msg\":\"table ready\"}";
            } catch (Exception e) {
                res.status(500);
                return err(e);
            }
        });

        // Insert a row: JSON body {"name":"Kathmandu","country":"NP"}
        post("/cities", (req, res) -> {
            try (Connection c = Db.conn();
                 PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO cities(name,country) VALUES (?,?)")) {
                Map<?,?> body = om.readValue(req.body(), Map.class);
                ps.setString(1, String.valueOf(body.get("name")));
                ps.setString(2, String.valueOf(body.get("country")));
                ps.executeUpdate();
                res.type("application/json");
                return "{\"ok\":true}";
            } catch (Exception e) {
                res.status(500);
                return err(e);
            }
        });

        // List rows
        get("/cities", (req, res) -> {
            try (Connection c = Db.conn();
                 Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                     "SELECT id,name,country,created_at FROM cities ORDER BY id DESC")) {
                List<Map<String,Object>> out = new ArrayList<>();
                while (rs.next()) {
                    Map<String,Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getInt("id"));
                    row.put("name", rs.getString("name"));
                    row.put("country", rs.getString("country"));
                    row.put("created_at", rs.getTimestamp("created_at"));
                    out.add(row);
                }
                res.type("application/json");
                return om.writeValueAsString(out);
            } catch (Exception e) {
                res.status(500);
                return err(e);
            }
        });

        System.out.println("Server started on port " + getPort());
        System.out.println("Endpoints: GET /health, POST /init, POST /cities, GET /cities");
    }

    private static int getPort() {
        String p = System.getenv("PORT");
        return (p == null || p.isBlank()) ? 8080 : Integer.parseInt(p);
    }

    private static String err(Exception e) {
        return "{\"ok\":false,\"error\":\"" + e.getMessage().replace("\"","\\\"") + "\"}";
    }
}

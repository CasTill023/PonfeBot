package music;


import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import java.nio.file.Files;
import java.nio.file.Paths;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import java.util.Timer;
import java.util.TimerTask;

import java.nio.charset.StandardCharsets;

    /**
     * EconomyManager - guarda los saldos en un JSON y ofrece métodos para manejar la economía.
     * Archivo JSON por defecto: data/economy.json
     */
    public class EconomyManager {

        // Mapa: userId -> (nombreMina -> timestampExpiración)
        private Map<String, Map<String, Long>> minasPrivadas = new HashMap<>();


        public static class UserData {
            long balance = 0;
            long lastDaily = 0; // epochDay (LocalDate.toEpochDay())
            long lastWork = 0; // timestamp del último trabajo
            long lastMine = 0;           // Para controlar cooldown de minar
            Map<String, Integer> inventory = new HashMap<>(); // inventario del usuario
            Map<String, Integer> items = new HashMap<>();    // ej: "pico"
            Map<String, Integer> minerals = new HashMap<>(); // ej: "oro", "plata"
        }

        private final Path file;
        private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
        private final Object lock = new Object();
        // map keys = userId as String
        private Map<String, UserData> map = new HashMap<>();

        // Shop (preparado para futuro). Cambia/añade items aquí.
        public final Map<String, Long> SHOP;
        public final long DAILY_AMOUNT = 100L;

        public EconomyManager() {
            this("data/economy.json");
        }

        public EconomyManager(String relativePath) {
            this.file = Paths.get(relativePath);

            // --- Aquí defines los ítems de la tienda ---
            Map<String, Long> tmp = new LinkedHashMap<>();
            tmp.put("ponfepapa", 5000L); // 👈 nuevo ítem agregado aquí
            tmp.put("pico", 250L); // Precio del pico
            tmp.put("minaplata", 10000L);
            tmp.put("minaoro", 30000L);
            tmp.put("minadiamante", 50000L);

            SHOP = Collections.unmodifiableMap(tmp);
            // ------------------------------------------

            try {
                init();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }


        private void init() throws IOException {
            Path parent = file.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            if (Files.exists(file)) {
                load();
            } else {
                save(); // crea fichero inicial
            }
        }

        private void load() {
            synchronized (lock) {
                try {
                    String json = Files.readString(file);
                    if (json == null || json.isBlank()) {
                        map = new HashMap<>();
                        return;
                    }
                    Type type = new TypeToken<Map<String, UserData>>() {
                    }.getType();
                    Map<String, UserData> loaded = gson.fromJson(json, type);
                    map = loaded != null ? loaded : new HashMap<>();
                } catch (IOException e) {
                    e.printStackTrace();
                    map = new HashMap<>();
                }
            }
        }

        private void save() {
            synchronized (lock) {
                try {
                    String json = gson.toJson(map);
                    Files.writeString(file, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        // Helpers
        public UserData getOrCreate(String id) {
            return map.computeIfAbsent(id, k -> new UserData());
        }

        // Consultas / operaciones
        public long getBalance(long userId) {
            synchronized (lock) {
                UserData u = map.get(String.valueOf(userId));
                return u == null ? 0L : u.balance;
            }
        }

        public void addBalance(long userId, long amount) {
            if (amount == 0) return;
            synchronized (lock) {
                UserData u = getOrCreate(String.valueOf(userId));
                u.balance = Math.max(0, u.balance + amount);
                save();
            }
        }

        /**
         * Intenta quitar amount del usuario. Devuelve true si se pudo (saldo suficiente), false si no.
         */
        public boolean removeBalance(long userId, long amount) {
            if (amount <= 0) return false;
            synchronized (lock) {
                UserData u = getOrCreate(String.valueOf(userId));
                if (u.balance < amount) return false;
                u.balance -= amount;
                save();
                return true;
            }
        }

        /**
         * Transferencia: devuelve true si se pudo efectuar.
         */
        public boolean transfer(long fromId, long toId, long amount) {
            if (amount <= 0) return false;
            synchronized (lock) {
                UserData from = getOrCreate(String.valueOf(fromId));
                if (from.balance < amount) return false;
                UserData to = getOrCreate(String.valueOf(toId));
                from.balance -= amount;
                to.balance += amount;
                save();
                return true;
            }
        }


        // Minerales
        public void addMineral(long userId, String key, int amount) {
            synchronized (lock) {
                UserData u = getOrCreate(String.valueOf(userId));
                u.minerals.put(key, u.minerals.getOrDefault(key, 0) + amount);
                save();
            }
        }

        public boolean removeMineral(long userId, String key, int amount) {
            synchronized (lock) {
                UserData u = getOrCreate(String.valueOf(userId));
                int current = u.minerals.getOrDefault(key, 0);
                if (current < amount) return false;
                u.minerals.put(key, current - amount);
                if (u.minerals.get(key) == 0) u.minerals.remove(key);
                save();
                return true;
            }
        }

        public Map<String, Integer> getItems(long userId) {
            synchronized (lock) {
                UserData u = map.get(String.valueOf(userId));
                return u == null ? new HashMap<>() : new HashMap<>(u.items);
            }
        }

        public Map<String, Integer> getMinerals(long userId) {
            synchronized (lock) {
                UserData u = map.get(String.valueOf(userId));
                return u == null ? new HashMap<>() : new HashMap<>(u.minerals);
            }
        }


        /**
         * Comprueba si puede reclamar daily. Si puede, actualiza lastDaily y añade DAILY_AMOUNT, retorna nuevo balance.
         * Si no puede (ya lo reclamó hoy) devuelve -1.
         */
        public long claimDaily(long userId) {
            synchronized (lock) {
                UserData u = getOrCreate(String.valueOf(userId));
                long today = LocalDate.now().toEpochDay();
                if (u.lastDaily >= today) {
                    return -1L;
                }
                u.balance += DAILY_AMOUNT;
                u.lastDaily = today;
                save();
                return u.balance;
            }
        }

        public boolean canClaimDaily(long userId) {
            synchronized (lock) {
                UserData u = map.get(String.valueOf(userId));
                long today = LocalDate.now().toEpochDay();
                return u == null || u.lastDaily < today;
            }
        }

        /**
         * Devuelve la lista top N (userId -> balance) ya ordenada descendente.
         */
        public List<Map.Entry<String, Long>> getTop(int n) {
            synchronized (lock) {
                return map.entrySet().stream()
                        .map(e -> Map.entry(e.getKey(), e.getValue().balance))
                        .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                        .limit(n)
                        .collect(Collectors.toList());
            }
        }

        // Métodos para la "tienda" básica (preparado)
        public Map<String, Long> getShop() {
            return SHOP;
        }

        /**
         * Compra un item (si existe y hay saldo). Devuelve:
         * 0 = item no existe
         * -1 = no saldo suficiente
         * >0 = nuevo balance tras la compra
         */
        public long buyItem(long userId, String itemKey) {
            Long price = SHOP.get(itemKey);
            if (price == null) return 0;
            synchronized (lock) {
                UserData u = getOrCreate(String.valueOf(userId));
                if (u.balance < price) return -1;
                u.balance -= price;
                // Aquí NO se realiza la "entrega" del item (ej. rol). Eso habría que implementarlo en Ponfe.
                save();
                return u.balance;
            }
        }

        public long work(long userId) {
            synchronized (lock) {
                UserData u = getOrCreate(String.valueOf(userId));
                long now = System.currentTimeMillis();
                long cooldown = 2 * 60 * 60 * 1000; // 2 horas

                if (u.lastWork != 0 && (now - u.lastWork) < cooldown) {
                    return -1L; // aún en cooldown
                }

                // recompensa aleatoria según "tipo de trabajo"
                String[] trabajos = {"programador", "repartidor", "cocinero", "diseñador", "mecánico"};
                int idx = new Random().nextInt(trabajos.length);
                String trabajo = trabajos[idx];
                long reward = 50 + new Random().nextInt(101); // entre 50 y 150 coins

                u.balance += reward;
                u.lastWork = now;
                save();
                return reward;
            }
        }

        // --- RUEDA DE LA RULETA ---
        public String playRoulette(long userId, String choice, long bet) {
            // Validar apuesta
            if (bet <= 0) {
                return "❌ La apuesta debe ser mayor que 0.";
            }

            if (!removeBalance(userId, bet)) {
                return "❌ No tienes suficiente saldo para apostar " + bet + " PonfeCoins.";
            }

            // Generar resultado aleatorio
            Random rand = new Random();
            int result = rand.nextInt(100); // 0 - 99
            String color;
            if (result < 48) color = "rojo";
            else if (result < 96) color = "negro";
            else color = "verde";

            // Calcular ganancias
            long winnings = 0;
            if (choice.equalsIgnoreCase(color)) {
                if (color.equals("verde")) winnings = bet * 14;
                else winnings = bet * 2;
                addBalance(userId, winnings);
                return "🎉 ¡Ganaste! Salió **" + color + "** y recibes " + winnings + " PonfeCoins.";
            } else {
                return "😢 Perdiste... salió **" + color + "**. Mejor suerte la próxima.";
            }
        }

        public void addTool(long userId, String item, int amount) {
            synchronized (lock) {
                UserData u = getOrCreate(String.valueOf(userId));
                u.items.put(item, u.items.getOrDefault(item, 0) + amount);
                save();
            }
        }


        // Quitar item del inventario
        public boolean removeTool(long userId, String item, int amount) {
            synchronized (lock) {
                UserData u = getOrCreate(String.valueOf(userId));
                int current = u.items.getOrDefault(item, 0);
                if (current < amount) return false;
                if (current == amount) u.items.remove(item);
                else u.items.put(item, current - amount);
                save();
                return true;
            }
        }

        // Obtener inventario
        public Map<String, Integer> getInventory(long userId) {
            synchronized (lock) {
                UserData u = getOrCreate(String.valueOf(userId));
                return new HashMap<>(u.inventory);
            }
        }

        public String mine(long userId) {
            synchronized (lock) {
                UserData u = getOrCreate(String.valueOf(userId));

                // Verificar pico
                int picos = u.items.getOrDefault("pico", 0);
                if (picos <= 0) return "❌ Necesitas un pico para minar. Ve a la tienda a comprar uno.";

                // Posibilidad de romper el pico
                if (new Random().nextDouble() < 0.1) { // 10%
                    removeTool(userId, "pico", 1); // si se rompe
                    return "💥 ¡Tu pico se ha roto mientras minabas!";
                }

                // Minerales con peso
                String[] minerales = {"oro", "plata", "diamante", "cobre", "uranio"};
                int[] pesos = {5, 15, 2, 30, 3};
                int total = Arrays.stream(pesos).sum();
                int r = new Random().nextInt(total);
                String mineral = null;
                for (int i = 0; i < minerales.length; i++) {
                    r -= pesos[i];
                    if (r < 0) {
                        mineral = minerales[i];
                        break;
                    }
                }

                int cantidad = 1 + new Random().nextInt(3); // 1 a 3
                addMineral(userId, mineral, cantidad);

                // Posibilidad de perder coins
                if (new Random().nextDouble() < 0.05) {
                    long perdida = Math.min(u.balance, 10 + new Random().nextInt(21));
                    removeBalance(userId, perdida);
                    return "⛏ Minaste " + cantidad + " " + mineral + " pero perdiste " + perdida + " PonfeCoins por accidente.";
                }

                return "⛏ Has minado " + cantidad + " " + mineral + "!";
            }
        }

        // 🔹 Activar acceso a una mina privada por 2 días (48h)
        public void darAccesoMina(long userId, String minaNombre) {
            synchronized (lock) {
                Map<String, Long> userMinas = minasPrivadas.getOrDefault(String.valueOf(userId), new HashMap<>());
                long expiracion = System.currentTimeMillis() + (48L * 60 * 60 * 1000); // 48 horas
                userMinas.put(minaNombre, expiracion);
                minasPrivadas.put(String.valueOf(userId), userMinas);
                save();
            }
        }

        // 🔹 Comprobar si el usuario tiene acceso vigente
        public boolean tieneAccesoMina(long userId, String minaNombre) {
            synchronized (lock) {
                Map<String, Long> userMinas = minasPrivadas.get(String.valueOf(userId));
                if (userMinas == null) return false;
                Long exp = userMinas.get(minaNombre);
                return exp != null && System.currentTimeMillis() < exp;
            }
        }

        // 🔹 Quitar acceso a la mina
        public void quitarAccesoMina(long userId, String minaNombre) {
            synchronized (lock) {
                Map<String, Long> userMinas = minasPrivadas.get(String.valueOf(userId));
                if (userMinas != null) {
                    userMinas.remove(minaNombre);
                    if (userMinas.isEmpty()) minasPrivadas.remove(String.valueOf(userId));
                    save();
                }
            }
        }

        // 🔹 Obtener todas las minas activas (para el chequeo automático)
        public Map<String, Map<String, Long>> getMinasPrivadas() {
            synchronized (lock) {
                return new HashMap<>(minasPrivadas);
            }
        }

        public static void iniciarChequeoMinas(JDA jda, EconomyManager economy) {
            // Se ejecuta cada 10 minutos para revisar expiraciones
            java.util.Timer timer = new java.util.Timer(true);
            timer.scheduleAtFixedRate(new java.util.TimerTask() {
                @Override
                public void run() {
                    economy.checkMinaExpiraciones(jda);
                }
            }, 0, 10 * 60 * 1000); // cada 10 minutos
        }

        public void checkMinaExpiraciones(JDA jda) {
            synchronized (lock) {
                long ahora = System.currentTimeMillis();
                List<String> usuariosAActualizar = new ArrayList<>();

                for (Map.Entry<String, Map<String, Long>> entry : minasPrivadas.entrySet()) {
                    String userId = entry.getKey();
                    Map<String, Long> minasUsuario = entry.getValue();

                    // Filtra minas que aún no expiran
                    Map<String, Long> minasActivas = minasUsuario.entrySet().stream()
                            .filter(e -> e.getValue() > ahora)
                            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

                    // Si hay cambios (alguna expiró)
                    if (minasActivas.size() != minasUsuario.size()) {
                        minasPrivadas.put(userId, minasActivas);
                        usuariosAActualizar.add(userId);
                    }
                }

                // Limpia usuarios sin minas activas
                minasPrivadas.entrySet().removeIf(e -> e.getValue().isEmpty());

                // Guarda los cambios
                if (!usuariosAActualizar.isEmpty()) {
                    save();
                }
            }


        }
    }



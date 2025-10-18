package music;

import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;



import javax.security.auth.login.LoginException;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Arrays;
import java.util.HashMap;

import static music.EconomyManager.iniciarChequeoMinas;


public class Ponfe extends ListenerAdapter {

    private final EconomyManager economy = new EconomyManager(); // maneja data/economy.json
    private final Random random = new Random();
    private static final long MAX_BET = 3000L;
    private static final long ROLE_PONFEPAPA_ID = 1420829567727177789L; // Reemplaza con el ID del rol real
    private static final long ANUNCIO_CHANNEL_ID = 1420828802308767765L; // Reemplaza con el ID del canal de anuncios
    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1).toLowerCase();
    }


    // --- SISTEMA DE APUESTAS F1 ---
    private boolean f1Active = false;
    private final Map<Long, Bet> f1Bets = new HashMap<>();
    private final List<String> pilots = Arrays.asList(
            "Verstappen", "Leclerc", "Piastri", "Hamilton", "Alonso", "Sainz"
    );

    private static class Bet {
        String pilot;
        long amount;

        Bet(String pilot, long amount) {
            this.pilot = pilot;
            this.amount = amount;
        }
    }


    public static void main(String[] args) throws LoginException {
        JDABuilder.createDefault("DISCORD_TOKEN") // <-- pon tu token aquí
                .enableIntents(GatewayIntent.MESSAGE_CONTENT, GatewayIntent.GUILD_MESSAGES, GatewayIntent.GUILD_VOICE_STATES)
                .addEventListeners(new Ponfe())
                .build();
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        String msg = event.getMessage().getContentRaw();

        // Ignorar bots
        if (event.getAuthor().isBot()) return;

        String raw = event.getMessage().getContentRaw().trim();
        String lower = raw.toLowerCase();

        // --- COMANDOS DE ECONOMÍA ---

        if (raw.startsWith("!ruleta")) {
            String[] parts = raw.split("\\s+");
            if (parts.length < 3) {
                event.getChannel().sendMessage("❌ Uso correcto: `!ruleta <rojo|negro|verde> <cantidad>`").queue();
                return;
            }

            String choice = parts[1];
            long bet;
            try {
                bet = Long.parseLong(parts[2]);
            } catch (NumberFormatException e) {
                event.getChannel().sendMessage("❌ La cantidad debe ser un número.").queue();
                return;
            }

            long userId = event.getAuthor().getIdLong();
            String result = economy.playRoulette(userId, choice, bet);

            event.getChannel().sendMessage(result).queue();
        }


        // !balance
        if (lower.equals("!balance") || lower.startsWith("!balance ")) {
            if (!event.isFromGuild()) {
                event.getChannel().sendMessage("Usa este comando en un servidor.").queue();
                return;
            }
            long targetId = event.getAuthor().getIdLong();
            List<net.dv8tion.jda.api.entities.User> mentions = event.getMessage().getMentions().getUsers();
            if (!mentions.isEmpty()) targetId = mentions.get(0).getIdLong();

            long bal = economy.getBalance(targetId);
            event.getChannel().sendMessage("💰 Saldo: **" + bal + "** PonfeCoin(s)").queue();
            return;
        }

        // !daily
        if (lower.equals("!daily")) {
            long userId = event.getAuthor().getIdLong();
            long result = economy.claimDaily(userId);
            if (result < 0) {
                event.getChannel().sendMessage("Ya has reclamado la recompensa diaria hoy. Vuelve mañana.").queue();
            } else {
                event.getChannel().sendMessage("Has recibido **" + economy.DAILY_AMOUNT + "** PonfeCoins. Nuevo saldo: **" + result + "**").queue();
            }
            return;
        }

        // !pay @usuario cantidad
        if (lower.startsWith("!pay ")) {
            List<net.dv8tion.jda.api.entities.User> mentions = event.getMessage().getMentions().getUsers();
            if (mentions.isEmpty()) {
                event.getChannel().sendMessage("Uso: `!pay @usuario cantidad`").queue();
                return;
            }
            long toId = mentions.get(0).getIdLong();
            String[] parts = raw.split("\\s+");
            if (parts.length < 3) {
                event.getChannel().sendMessage("Uso: `!pay @usuario cantidad`").queue();
                return;
            }
            long amount;
            try {
                amount = Long.parseLong(parts[2]);
            } catch (NumberFormatException ex) {
                event.getChannel().sendMessage("Cantidad no válida.").queue();
                return;
            }
            long fromId = event.getAuthor().getIdLong();
            if (amount <= 0) {
                event.getChannel().sendMessage("La cantidad debe ser mayor que 0.").queue();
                return;
            }
            boolean ok = economy.transfer(fromId, toId, amount);
            if (ok) {
                event.getChannel().sendMessage("Has enviado **" + amount + "** PonfeCoins.").queue();
            } else {
                event.getChannel().sendMessage("No tienes suficiente saldo.").queue();
            }
            return;
        }

        // !addcoins @usuario cantidad   (admin)
        if (lower.startsWith("!addcoins ")) {
            if (event.getMember() == null || !event.getMember().hasPermission(Permission.ADMINISTRATOR)) {
                event.getChannel().sendMessage("Necesitas permisos de administrador para usar este comando.").queue();
                return;
            }
            List<net.dv8tion.jda.api.entities.User> mentions = event.getMessage().getMentions().getUsers();
            if (mentions.isEmpty()) {
                event.getChannel().sendMessage("Uso: `!addcoins @usuario cantidad`").queue();
                return;
            }
            long targetId = mentions.get(0).getIdLong();
            String[] parts = raw.split("\\s+");
            if (parts.length < 3) {
                event.getChannel().sendMessage("Uso: `!addcoins @usuario cantidad`").queue();
                return;
            }
            long amount;
            try {
                amount = Long.parseLong(parts[2]);
            } catch (NumberFormatException ex) {
                event.getChannel().sendMessage("Cantidad no válida.").queue();
                return;
            }
            economy.addBalance(targetId, amount);
            event.getChannel().sendMessage("Se han añadido **" + amount + "** PonfeCoins al usuario.").queue();
            return;
        }

        // !leaderboard
        if (lower.equals("!leaderboard") || lower.equals("!top")) {
            List<Map.Entry<String, Long>> top = economy.getTop(10);
            if (top.isEmpty()) {
                event.getChannel().sendMessage("No hay datos todavía.").queue();
                return;
            }
            StringBuilder sb = new StringBuilder("🏆 Top PonfeCoin:\n");
            int i = 1;
            for (Map.Entry<String, Long> e : top) {
                sb.append(i).append(". <@").append(e.getKey()).append("> — **").append(e.getValue()).append("**\n");
                i++;
            }
            event.getChannel().sendMessage(sb.toString()).queue();
            return;
        }

        // !bet cantidad  (juego 50/50)
        if (lower.startsWith("!bet ")) {
            String[] parts = raw.split("\\s+");
            if (parts.length < 2) {
                event.getChannel().sendMessage("Uso: `!bet cantidad`").queue();
                return;
            }
            long amount;
            try {
                amount = Long.parseLong(parts[1]);
            } catch (NumberFormatException ex) {
                event.getChannel().sendMessage("Cantidad no válida.").queue();
                return;
            }
            long userId = event.getAuthor().getIdLong();
            long balance = economy.getBalance(userId);
            if (amount > MAX_BET) {
                event.getChannel().sendMessage("La apuesta máxima es de " + MAX_BET + " PonfeCoins.").queue();
                return;
            }

            if (amount <= 0 || amount > balance) {
                event.getChannel().sendMessage("Cantidad inválida o no tienes suficiente saldo.").queue();
                return;
            }
            boolean win = random.nextBoolean(); // 50/50
            if (win) {
                economy.addBalance(userId, amount);
                event.getChannel().sendMessage("¡Has ganado! 🎉 Ganaste **" + amount + "** PonfeCoins. Nuevo saldo: **" + economy.getBalance(userId) + "**").queue();
            } else {
                economy.removeBalance(userId, amount);
                event.getChannel().sendMessage("Has perdido 😢 Perdiste **" + amount + "** PonfeCoins. Nuevo saldo: **" + economy.getBalance(userId) + "**").queue();
            }
            return;
        }

        // --- SISTEMA DE CARRERAS F1 ---

// !f1start → inicia una nueva carrera
        if (lower.equals("!f1start")) {
            if (f1Active) {
                event.getChannel().sendMessage("🏎️ Ya hay una carrera en curso. Usa **!f1race** para finalizarla.").queue();
                return;
            }
            f1Active = true;
            f1Bets.clear();
            StringBuilder sb = new StringBuilder("🏁 **Carrera F1 iniciada** 🏁\nPilotos disponibles:\n");
            for (String p : pilots) sb.append("- ").append(p).append("\n");
            sb.append("\nApuesta con: `!f1bet <piloto> <cantidad>`");
            event.getChannel().sendMessage(sb.toString()).queue();
            return;
        }

// !f1bet <piloto> <cantidad> → apostar
        if (lower.startsWith("!f1bet ")) {
            if (!f1Active) {
                event.getChannel().sendMessage("❌ No hay ninguna carrera activa. Usa **!f1start** para comenzar una.").queue();
                return;
            }

            String[] parts = raw.split("\\s+");
            if (parts.length < 3) {
                event.getChannel().sendMessage("Uso: !f1bet <piloto> <cantidad>").queue();
                return;
            }

            String chosenPilot = parts[1];
            if (!pilots.contains(chosenPilot)) {
                event.getChannel().sendMessage("Ese piloto no está en la lista.").queue();
                return;
            }

            long amount;
            try {
                amount = Long.parseLong(parts[2]);
            } catch (NumberFormatException e) {
                event.getChannel().sendMessage("Cantidad no válida.").queue();
                return;
            }

            if (amount <= 0 || amount > MAX_BET) {
                event.getChannel().sendMessage("Puedes apostar entre 1 y " + MAX_BET + " PonfeCoins.").queue();
                return;
            }

            long userId = event.getAuthor().getIdLong();
            long balance = economy.getBalance(userId);
            if (balance < amount) {
                event.getChannel().sendMessage("No tienes suficiente saldo.").queue();
                return;
            }

            // guardar apuesta
            f1Bets.put(userId, new Bet(chosenPilot, amount));
            economy.removeBalance(userId, amount);
            event.getChannel().sendMessage("✅ Has apostado **" + amount + "** PonfeCoins a **" + chosenPilot + "**.").queue();
            return;
        }

// !f1race → termina la carrera
        if (lower.equals("!f1race")) {
            if (!f1Active) {
                event.getChannel().sendMessage("No hay ninguna carrera activa.").queue();
                return;
            }

            if (f1Bets.isEmpty()) {
                event.getChannel().sendMessage("Nadie ha apostado todavía.").queue();
                return;
            }

            String winner = pilots.get(random.nextInt(pilots.size()));
            StringBuilder sb = new StringBuilder("🏁 **Carrera finalizada** 🏁\nGanador: **" + winner + "**\n\n");

            for (Map.Entry<Long, Bet> e : f1Bets.entrySet()) {
                long userId = e.getKey();
                Bet bet = e.getValue();
                if (bet.pilot.equals(winner)) {
                    long reward = bet.amount * 5; // paga x5
                    economy.addBalance(userId, reward);
                    sb.append("<@").append(userId).append("> ganó **").append(reward).append("** PonfeCoins 🎉\n");
                } else {
                    sb.append("<@").append(userId).append("> perdió su apuesta 😢\n");
                }
            }

            f1Active = false;
            f1Bets.clear();

            event.getChannel().sendMessage(sb.toString()).queue();
            return;
        }

        // 🎰 COMANDO SLOTS
        else if (msg.startsWith("!slot")) {
            String[] parts = msg.split("\\s+");
            if (parts.length < 2) {
                event.getChannel().sendMessage("💰 Uso correcto: `!slot <cantidad>`").queue();
                return;
            }

            long userId = event.getAuthor().getIdLong();
            long bet;

            try {
                bet = Long.parseLong(parts[1]);
            } catch (NumberFormatException e) {
                event.getChannel().sendMessage("⚠️ Por favor, ingresa una cantidad válida.").queue();
                return;
            }

            long balance = economy.getBalance(userId);
            long MAX_BET = 1500;

            if (bet <= 0) {
                event.getChannel().sendMessage("⚠️ La apuesta debe ser mayor que 0.").queue();
                return;
            }

            if (bet > MAX_BET) {
                event.getChannel().sendMessage("🚫 El máximo permitido por apuesta es de " + MAX_BET + " 💰 PonfeCoins.").queue();
                return;
            }

            if (balance < bet) {
                event.getChannel().sendMessage("💸 No tienes suficientes PonfeCoins para apostar esa cantidad.").queue();
                return;
            }

            // Cobrar apuesta
            economy.removeBalance(userId, bet);

            // Símbolos de la tragaperras
            String[] symbols = {"🍒", "🍋", "🍇", "💎", "🔔", "7️⃣"};
            Random random = new Random();

            String s1 = symbols[random.nextInt(symbols.length)];
            String s2 = symbols[random.nextInt(symbols.length)];
            String s3 = symbols[random.nextInt(symbols.length)];

            long multiplier = 0;
            if (s1.equals(s2) && s2.equals(s3)) {
                multiplier = s1.equals("7️⃣") ? 10 : 5; // 3 iguales
            } else if (s1.equals(s2) || s1.equals(s3) || s2.equals(s3)) {
                multiplier = 2; // 2 iguales
            }

            String result = "🎰 | " + s1 + " " + s2 + " " + s3 + "\n";

            if (multiplier > 0) {
                long winnings = bet * multiplier;
                economy.addBalance(userId, winnings);
                result += "🎉 ¡Ganaste **" + winnings + "** PonfeCoins! (x" + multiplier + ")\n";
            } else {
                result += "😢 No hubo suerte esta vez. Has perdido **" + bet + "** PonfeCoins.\n";
            }

            long newBalance = economy.getBalance(userId);
            result += "💰 Tu nuevo balance: **" + newBalance + "** PonfeCoins.";

            event.getChannel().sendMessage(result).queue();
        }

        if (msg.equalsIgnoreCase("!trabajar")) {
            long userId = event.getAuthor().getIdLong();

            // chequeamos si ya trabajó hoy
            long reward = economy.work(userId);
            if (reward == -1L) {
                event.getChannel().sendMessage("💼 Ya has trabajado recientemente. ¡Vuelve más tarde!").queue();
                return;
            }

            event.getChannel().sendMessage("💰 Has trabajado duro y has ganado " + reward + " PonfeCoins.").queue();
        }

        else if (msg.startsWith("!buy ")) {
            String item = msg.substring(5).trim().toLowerCase();
            long userId = event.getAuthor().getIdLong();

            long result = economy.buyItem(userId, item);

            if (result == 0) {
                event.getChannel().sendMessage("❌ Ese item no existe en la tienda.").queue();
                return;
            } else if (result == -1) {
                event.getChannel().sendMessage("💸 No tienes suficientes PonfeCoins.").queue();
                return;
            }

            // ✅ Si el item es el rol PonfePapa
            if (item.equals("ponfepapa")) {
                var guild = event.getGuild();
                var newOwner = event.getMember();
                var role = guild.getRoleById(ROLE_PONFEPAPA_ID);
                var anuncio = guild.getTextChannelById(ANUNCIO_CHANNEL_ID);

                if (role == null) {
                    event.getChannel().sendMessage("⚠️ No se encontró el rol PonfePapa en el servidor.").queue();
                    return;
                }

                // Buscar y eliminar el rol del antiguo dueño
                var oldOwners = guild.getMembersWithRoles(role);
                for (var oldOwner : oldOwners) {
                    guild.removeRoleFromMember(oldOwner, role).queue();
                }

                // Dar el rol al nuevo comprador
                guild.addRoleToMember(newOwner, role).queue();

                // Mensaje privado al comprador
                event.getChannel().sendMessage("👑 ¡Has comprado el rol **PonfePapa** por 5000 PonfeCoins!").queue();

                // Anuncio público
                if (anuncio != null) {
                    anuncio.sendMessage("🎉 ¡**" + newOwner.getEffectiveName() + "** es el nuevo **PonfePapa** ! 👑").queue();
                }
            } // en Ponfe.java

            // 🪓 Si compra una mina privada
            if (item.equals("minaoro") || item.equals("minaplata") || item.equals("minadiamante")) {
                Guild guild = event.getGuild();
                Member member = event.getMember();

                // IDs de los roles de las minas (ajusta con los tuyos)
                String ROLE_ID_ORO = "1426123316682428489";
                String ROLE_ID_PLATA = "1426123188038926346";
                String ROLE_ID_DIAMANTE = "1426123373540413480";

                Role role = null;
                if (item.equals("minaoro")) role = guild.getRoleById(ROLE_ID_ORO);
                if (item.equals("minaplata")) role = guild.getRoleById(ROLE_ID_PLATA);
                if (item.equals("minadiamante")) role = guild.getRoleById(ROLE_ID_DIAMANTE);

                if (role == null) {
                    event.getChannel().sendMessage("⚠️ No se encontró el rol de esa mina.").queue();
                    return;
                }

                // Añadir el rol al usuario
                guild.addRoleToMember(member, role).queue();

                // Guardar acceso temporal en la economía
                economy.darAccesoMina(userId, item);

                event.getChannel().sendMessage("⛏️ ¡Has comprado acceso a la **" + item + "** por 2 días!").queue();
                return;
            }
            else {
                // Otros items genéricos
                economy.addTool(userId, item, 1);
                event.getChannel().sendMessage("🛒 Has comprado **" + item + "**. Nuevo saldo: " + result + " PonfeCoins.").queue();
            }

        }

        else if (msg.equals("!shop")) {
            StringBuilder sb = new StringBuilder("🛍️ **Tienda PonfeCoin**\n");
            for (Map.Entry<String, Long> entry : economy.getShop().entrySet()) {
                sb.append("• ").append(entry.getKey()).append(" — 💰 ").append(entry.getValue()).append(" PonfeCoins\n");
            }
            event.getChannel().sendMessage(sb.toString()).queue();
        }
        if (lower.equals("!minar")) {
            long userId = event.getAuthor().getIdLong();
            String result = economy.mine(userId);
            event.getChannel().sendMessage(result).queue();
        }
        if (lower.equals("!inv")) {
            long userId = event.getAuthor().getIdLong();
            Map<String,Integer> items = economy.getItems(userId);
            Map<String,Integer> minerals = economy.getMinerals(userId);

            StringBuilder sb = new StringBuilder("📦 Tu inventario:\n");
            if (items.isEmpty() && minerals.isEmpty()) sb.append("Vacío.");
            else {
                if (!items.isEmpty()) {
                    sb.append("🔧 Objetos:\n");
                    items.forEach((k,v) -> sb.append("- ").append(k).append(": ").append(v).append("\n"));
                }
                if (!minerals.isEmpty()) {
                    sb.append("💎 Minerales:\n");
                    minerals.forEach((k,v) -> sb.append("- ").append(k).append(": ").append(v).append("\n"));
                }
            }
            event.getChannel().sendMessage(sb.toString()).queue();
        }
        if (lower.startsWith("!vender ")) {
            long userId = event.getAuthor().getIdLong();
            String[] parts = raw.split("\\s+");
            if (parts.length < 3) {
                event.getChannel().sendMessage("Uso: !vender <mineral> <cantidad>").queue();
                return;
            }

            String mineral = parts[1].toLowerCase();
            int cantidad;
            try { cantidad = Integer.parseInt(parts[2]); }
            catch (NumberFormatException e) {
                event.getChannel().sendMessage("Cantidad no válida.").queue();
                return;
            }

            Map<String,Integer> inventario = economy.getMinerals(userId);
            int disponible = inventario.getOrDefault(mineral,0);
            if (disponible < cantidad) {
                event.getChannel().sendMessage("❌ No tienes suficientes " + mineral + ".").queue();
                return;
            }

            Map<String,Integer> precios = Map.of(
                    "oro",80,
                    "plata",55,
                    "diamante",155,
                    "cobre",40
            );

            Integer precio = precios.get(mineral);
            if (precio==null){
                event.getChannel().sendMessage("❌ Mineral no vendible.").queue();
                return;
            }

            long ganancia = precio*cantidad;
            economy.removeMineral(userId,mineral,cantidad);
            economy.addBalance(userId,ganancia);

            event.getChannel().sendMessage("💰 Has vendido " + cantidad + " " + mineral + " por " + ganancia + " PonfeCoins.").queue();
        }
        else if (msg.startsWith("!additem ")) {
            if (!event.getMember().hasPermission(Permission.ADMINISTRATOR)) {
                event.getChannel().sendMessage("🚫 No tienes permiso para usar este comando.").queue();
                return;
            }

            String[] parts = msg.split(" ");
            if (parts.length < 4) {
                event.getChannel().sendMessage("❌ Uso correcto: `!additem @usuario objeto cantidad`").queue();
                return;
            }

            Member target = event.getMessage().getMentions().getMembers().get(0);
            if (target == null) {
                event.getChannel().sendMessage("⚠️ Debes mencionar a un usuario.").queue();
                return;
            }

            String item = parts[2].toLowerCase();
            int amount;
            try {
                amount = Integer.parseInt(parts[3]);
            } catch (NumberFormatException e) {
                event.getChannel().sendMessage("⚠️ La cantidad debe ser un número.").queue();
                return;
            }

            economy.addTool(target.getIdLong(), item, amount);
            event.getChannel().sendMessage("✅ Se añadieron **" + amount + " " + item + "** al inventario de **" + target.getEffectiveName() + "**.").queue();
        }
        else if (msg.startsWith("!removeitem ")) {
            if (!event.getMember().hasPermission(Permission.ADMINISTRATOR)) {
                event.getChannel().sendMessage("🚫 No tienes permiso para usar este comando.").queue();
                return;
            }

            String[] parts = msg.split(" ");
            if (parts.length < 4) {
                event.getChannel().sendMessage("❌ Uso correcto: `!additem @usuario objeto cantidad`").queue();
                return;
            }

            Member target = event.getMessage().getMentions().getMembers().get(0);
            if (target == null) {
                event.getChannel().sendMessage("⚠️ Debes mencionar a un usuario.").queue();
                return;
            }

            String item = parts[2].toLowerCase();
            int amount;
            try {
                amount = Integer.parseInt(parts[3]);
            } catch (NumberFormatException e) {
                event.getChannel().sendMessage("⚠️ La cantidad debe ser un número.").queue();
                return;
            }

            economy.removeTool(target.getIdLong(), item, amount);
            event.getChannel().sendMessage("✅ Se eliminaron **" + amount + " " + item + "** al inventario de **" + target.getEffectiveName() + "**.").queue();
        }

        // --- COMANDO DE FRASES ALEATORIAS (ponfeley) ---
        if (raw.equalsIgnoreCase("ponfeley")) {
            String[] frases = {
                    "*Mojopicón*",
                    "*Para los mortales, shift coseno*",
                    "*Deja de prohibirme tanto que ya no tengo tiempo a desobedecer tantas cosas*",
                    "*No os preocupéis que yo me lo invento todo y no tiene nada que ver con matemáticas*",
                    "*Tenéis que explotar en 2º de Bachillerato*",
                    "*Yo he dicho la verdad absoluta*",
                    "*Cuando os arranque la cabeza ya no tendrá gracia*",
                    "*Os voy a decir una cosa: tengo un máster en parrillas.*",
                    "*Entiendo que sea difícil, pero es sencillo*",
                    "*Cuidado con los pisa-hormigas*",
                    "*Una discontinuidad es finita si no es infinita.*",
                    "*Cuando vosotros teníais 20 años, no, cuando yo tenía 20 años, vosotros teníais 0 años.*",
                    "*Todos los que tenemos 40 años en el colegio somos listos, los que no, no.*",
                    "*Cuando yo estoy explicando y vosotros hablando, son C.*",
                    "*Os pongo este examen el lunes para recuperar el que vais a tener el jueves.*",
                    "*<¿Esto entra el jueves?> No, pero sí el lunes.*",
                    "*Dejar el problema en blanco es no saber hacerlo.*",
                    "*Esto para mí son obras de arte, pero yo de arte no entiendo.*",
                    "*No te pongas un reto, ponte un reto pequeño, verás como no lo consigas.*",
                    "*¿En qué consiste la optimización? En optimizar.*",
                    "*(Tras poner una C) Me caliento, uf que calor me entra.*",
                    "*Seréis hombres cuando os caséis y tengáis hijos.*",
                    "*<¿Pero esto no es peor?...> No no no… bueno sí.*",
                    "*Falta carencia, ¿cómo se dice en el toreo?, no sé lo que es, pero falta carencia.*",
                    "*Si no lo entiendes, te lo crees.*",
                    "*Nunca me había mirado tanta gente, me pongo nervioso, y además siendo todos hombres.*",
                    "*Pensad que cada vez sabéis más, y cada vez sabéis menos.*",
                    "*Ustedes hacéis lo que os sale del pie, así que voy a hacer lo que me salga del lápiz.*",
                    "*(Se acaba de equivocar en una suma) Me puedo equivocar en muchas cosas, menos en matemáticas.*",
                    "*Al “disminuinui” se encoge el dibujo.*",
                    "*Yo ya soy viejo, me puedo equivocar.*",
                    "*Los problemas que faltan son los que no están.*",
                    "*Nunca os voy a preguntar cosas de la pizarra, os voy a preguntar lo que expreso.*",
                    "*Tengo que encontrar el máximo de la pendiente, por eso tengo que encontrar el máximo de la pendiente.*",
                    "*No lo voy a poner ni difícil, ni muy difícil.*",
                    "*Como se levante un tío de la mesa, no, de la silla, le pongo una C.*",
                    "*Si se dais cuenta, la tabla de las derivadas es igual que la de las integrales. Es al revés, o sea igual.*",
                    "*Entran siempre, puede que sí o puede que no entren.*",
                    "*No lo ves, porque no lo veías, pero a partir de ahora, lo ves.*",
                    "*Esto es como cuando vas a un tribunal americano y dices: “No tengo nada que declarar”.*",
                    "*No voy a poner tipos que luego os liáis… Tipo uno.*",
                    "*<¿No eran procedimentales?...> No, ahora te enseño el procedimiento.*",
                    "*Es que ustedes no tenéis complejo de vaca, porque las vacas comen y después en casa “grumian”.*",
                    "*Vamos a dar integrales con el denominador mayor en el numerador que en el denominador.*",
                    "*Hay cosas que no voy a evaluar, las cosas que no voy a evaluar son… Lo evalúo todo.*",
                    "*Esto no está al revés, el que está al revés soy yo.*",
                    "*Lo peor de todo es que cuando termina el colegio me pego un pedo y digo: “ahí se queda todo”.*",
                    "*No va a venir el Espíritu Santo y os va a decir la derivada, y si lo hace, aprovechadlo.*",
                    "*<Don José una pregunta-> No me rayes.*",
                    "*Yo me estoy esforzando porque se entere y tú porque no, esto genera un conflicto.*",
                    "*(Se equivoca operando con los signos) ¡Qué bien me ha salido! (mira a un alumno y sonríe).*",
                    "*Ha dicho usted no sé qué de cambio variable… (Se queda pensando en silencio).*",
                    "*No te creas que las cosas son como tú las piensas.*",
                    "*Echadle cuenta a Don José Antonio, menos cuando no es para matemáticas.*",
                    "*Al igual que puntúo lo que está hecho, tampoco puntúo lo que no está hecho.*",
                    "*Yo voy a hacer lo que esté en mi mano, lo que esté en vuestra mano lo tenéis que hacer ustedes.*",
                    "*La vida es dura.*",
                    "*Tengo tres alumnos que están pendientes de continuar con su vida.*",
                    "*Sí… bueno, no.*",
                    "*La pregunta es: “Don José Antonio, estamos locos, ¿quiere volverse loco con nosotros?”.*",
                    "*Entonces vamos a ir por… donde íbamos.*",
                    "*Un coche de bomberos, un coche de policía, dos Barbies y unos patines, bueno los patines a mí no porque no me caben, pero con el resto te tiras al suelo y te pones a jugar en la alfombra de tu casa.*",
                    "*¿Cómo se multiplican matrices?, que me lo diga el que no tenga ni idea.*",
                    "*Ya está, ya está, que no quiero seguir hablando.*",
                    "*Si os digo este método lo vais a hacer así, así que no sé si decíroslo.*",
                    "*Por poquito que hagas, puedes hacer mucho más.*",
                    "*Es bueno especializarse en algo, como en matemáticas, porque después me preguntáis por otra cosa y sabéis aún más que yo.*",
                    "*Un ejemplo: “Vamos a poner un ejemplo”.*",
                    "*Vamos a poner un ejemplo de cómo poner una C: “Si un alumno levanta la mano sin decírselo antes al profesor, se lleva una C.”*",
                    "*Tengo que saber hasta dónde sabéis y no sabéis nada.*",
                    "*El problema no está en que el profesor tenga el problema sino en la pizarra.*",
                    "*No pero… no, no pero… no.*",
                    "*Hoy en día puede dar clase cualquiera.*",
                    "*Eah, clase normal.*",
                    "*Podría ser, pero ya no puede ser.*",
                    "*La verdad pura y dura… la verdad de la buena.*",
                    "*Es más fácil lo que se ve, que lo que os aprendéis.*",
                    "*Un profesor listo no lo escribiría, pero como yo no lo soy lo escribo.*",
                    "*Yo os he enseñado cómo se tira un penalti, ahora ustedes tenéis que practicar para meterlo.*",
                    "*Sois muy peligrosos… Tenéis una edad muy peligrosa.*",
                    "*Voy a ser malo, voy a ser malo, ¿nos vamos ya?, <sí>, no.*",
                    "*Me estoy aquí entreteniendo y ya no tengo ansiedad.*",
                    "*Coño que me siento.*",
                    "*Yo estoy peor… En todos los sentidos.*",
                    "*Si no tienes permiso no puedes hablar, por mucho que levantes la mano.*",
                    "*¿Pero cómo qué me va a dar igual?*",
                    "*¿Cuántas incógnitas veis? <Dos>. Pues si veis dos, es que hay dos, no os inventéis.*",
                    "*No sé qué ha querido decir, pero algo ha querido decir.*",
                    "*Perdóname que no ponga el trece, porque si lo pongo me da mala suerte.*",
                    "*Voy preguntando preguntitas que no suelen preguntar.*",
                    "*Que yo sea bueno no significa que esté mal clasificado.*",
                    "*El lápiz no se ha equivocado.*",
                    "*¿Y qué punto puedo coger para el examen?, ninguno, porque me he equivocado.*",
                    "*Los profesores son malvados, se comen los pájaros.*",
                    "*Voy a por ti para que sepas que es por ti.*",
                    "*(Realizando un ejercicio de la ficha 2) Esto es un problema de equidistancias, áreas… ¿a qué os suena esto?, esto suena a ficha 2.*",
                    "*Si se ponéis en mis manos la vais a liar.*",
                    "*<¿Si lo pongo más bonito puntúa más?> Eso suena a si vas al desierto y tienes que racionalizar el agua.*"
            };
            String fraseAleatoria = frases[random.nextInt(frases.length)];
            event.getChannel().sendMessage(fraseAleatoria).queue();
        }
    }

    @Override
    public void onReady(ReadyEvent event) {
        String channelId = "1420828802308767765";
        GuildMessageChannel canal = event.getJDA().getChannelById(GuildMessageChannel.class, channelId);
        if (canal != null) {
            canal.sendMessage("*Buenos días. Evidentemente, yo vengo de la feria empalmao.*").queue();
        }
        iniciarChequeoMinas(event.getJDA(), economy);
        System.out.println("✅ Chequeo automático de minas privadas activado.");
    }


}


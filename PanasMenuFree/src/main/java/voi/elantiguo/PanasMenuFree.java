package voi.elantiguo;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class PanasMenuFree extends JavaPlugin implements Listener {
    private final Map<String, List<String>> friendGroups = new HashMap<>();
    private static final String MAIN_MENU_TITLE = "Gestión de Amigos";
    private final Map<Player, PendingAction> pendingActions = new HashMap<>();
    private Inventory mainMenu;
    private File dataFile;
    private FileConfiguration dataConfig;
    private Map<String, List<String>> friends = new HashMap<>();
    private Map<String, List<String>> friendRequests = new HashMap<>();
    private Map<String, Set<String>> blockedPlayers = new HashMap<>();

    // Enum para acciones pendientes
    private enum PendingAction {
        ADD_FRIEND, REMOVE_FRIEND, BLOCK_FRIEND, UNBLOCK_FRIEND, SEND_GIFT
    }

    @Override
    public void onEnable() {
        getLogger().info("Plugin PanasMenu activado.");
        saveDefaultConfig();
        registerCommands();
        getServer().getPluginManager().registerEvents(this, this);
        setupMainMenu();

        loadData();
        getLogger().info("Inicialización completada.");
    }

    @Override
    public void onDisable() {
        saveData();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        notifyFriends(event.getPlayer(), "se ha conectado.");
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        notifyFriends(event.getPlayer(), "se ha desconectado.");
    }

    private void notifyFriends(Player player, String action) {
        for (String friendName : friends.getOrDefault(player.getName(), new ArrayList<>())) {
            Player friend = Bukkit.getPlayer(friendName);
            if (friend != null && friend.isOnline()) {
                friend.sendMessage(player.getName() + " " + action);
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player && MAIN_MENU_TITLE.equals(event.getView().getTitle())) {
            // No hagas nada aquí si no es necesario
            // Esto evita que se copien los items al inventario del jugador
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(colorize(getConfig().getString("messages.no_permission")));
            return true;
        }

        switch (command.getName().toLowerCase()) {
            case "panas":
                openMainMenu(player);
                return true;
            case "helppanas":
                showHelp(player);
                return true;
            case "aceptar":
                handleFriendRequest(player, args, true);
                return true;
            case "rechazar":
                handleFriendRequest(player, args, false);
                return true;
            case "panasreload":
                if (player.hasPermission("panasmenu.reload")) {
                    reloadConfig(); // Recarga la configuración
                    setupMainMenu(); // Vuelve a configurar el menú principal
                    player.sendMessage(colorize(getConfig().getString("messages.reload_success"))); // Asegúrate de tener este mensaje en config.yml
                } else {
                    player.sendMessage(colorize(getConfig().getString("messages.no_permission")));
                }
                return true;
            default:
                return false;
        }
    }

    private void registerCommands() {
        Objects.requireNonNull(getCommand("panas")).setExecutor(this);
        Objects.requireNonNull(getCommand("helppanas")).setExecutor(this);
        Objects.requireNonNull(getCommand("aceptar")).setExecutor(this);
        Objects.requireNonNull(getCommand("rechazar")).setExecutor(this);
        Objects.requireNonNull(getCommand("panasreload")).setExecutor(this);
    }

    @EventHandler
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof Player target && event .getPlayer().isSneaking()) {
            sendFriendRequest(event.getPlayer(), target.getName());
        }
    }

    public void sendFriendRequest(Player requester, String targetName) {
        Player target = Bukkit.getPlayer(targetName);
        if (target == null || !target.isOnline()) {
            requester.sendMessage(colorize(getConfig().getString("messages.player_not_online")));
            return;
        }

        if (friends.getOrDefault(requester.getName(), new ArrayList<>()).contains(targetName)) {
            requester.sendMessage(colorize(getConfig().getString("messages.already_friends").replace("%friend%", targetName)));
            return;
        }

        List<String> requests = friendRequests.getOrDefault(targetName, new ArrayList<>());
        if (!requests.contains(requester.getName())) {
            requests.add(requester.getName());
            friendRequests.put(targetName, requests);
            target.sendMessage(colorize(getConfig().getString("messages.friend_request_received").replace("%requester%", requester.getName())));
            requester.sendMessage(colorize(getConfig().getString("messages.friend_request_sent").replace("%target%", targetName)));
        } else {
            requester.sendMessage(colorize(getConfig().getString("messages.request_already_sent").replace("%friend%", targetName)));
        }
    }

    private void handleFriendRequest(Player player, String[] args, boolean accept) {
        if (args.length == 1) {
            String requesterName = args[0];
            Player requester = Bukkit.getPlayer(requesterName);
            if (requester == null || !requester.isOnline()) {
                player.sendMessage("El jugador no está en línea.");
                return;
            }

            if (accept) {
                acceptFriendRequest(player, requester);
            } else {
                rejectFriendRequest(player, requester);
            }
        } else {
            player.sendMessage("Uso: /" + (accept ? "aceptar" : "rechazar") + " <jugador>");
        }
    }

    private void acceptFriendRequest(Player player, Player requester) {
        if (!friendRequests.containsKey(requester.getName()) || !friendRequests.get(requester.getName()).contains(player.getName())) {
            player.sendMessage(colorize("No tienes una solicitud de amistad de " + requester.getName() + "."));
            return;
        }

        List<String> playerFriends = friends.getOrDefault(player.getName(), new ArrayList<>());
        List<String> requesterFriends = friends.getOrDefault(requester.getName(), new ArrayList<>());

        playerFriends.add(requester.getName());
        requesterFriends.add(player.getName());

        friends.put(player.getName(), playerFriends);
        friends.put(requester.getName(), requesterFriends);

        player.sendMessage(colorize(getConfig().getString("messages.friend_added").replace("%friend%", requester.getName())));
        requester.sendMessage(colorize(player.getName() + " ha aceptado tu solicitud de amistad."));

        friendRequests.get(requester.getName()).remove(player.getName());
        if (friendRequests.get(requester.getName()).isEmpty()) {
            friendRequests.remove(requester.getName());
        }

        saveData();
    }

    private void rejectFriendRequest(Player player, Player requester) {
        if (!friendRequests.containsKey(requester.getName()) || !friendRequests.get(requester.getName()).contains(player.getName())) {
            player.sendMessage("No tienes una solicitud de amistad de " + requester.getName() + ".");
            return;
        }

        friendRequests.get(requester.getName()).remove(player.getName());
        if (friendRequests.get(requester.getName()).isEmpty()) {
            friendRequests.remove(requester.getName());
        }

        player.sendMessage("Has rechazado la solicitud de amistad de " + requester.getName() + ".");
        requester.sendMessage(player.getName() + " ha rechazado tu solicitud de amistad.");

        saveData();
    }

    private void setupMainMenu() {
        mainMenu = Bukkit.createInventory(null, 36, MAIN_MENU_TITLE);
        mainMenu.setItem(10, createMenuItem(Material.EMERALD, "Añadir amigo", "Añadir un jugador a tu lista de amigos"));
        mainMenu.setItem(12, createMenuItem(Material.REDSTONE, "Eliminar amigo", "Eliminar un amigo de tu lista"));
        mainMenu.setItem(14, createMenuItem(Material.BARRIER, "Bloquear amigo", "Bloquear a un jugador"));
        mainMenu.setItem(16, createMenuItem(Material.IRON_BARS, "Desbloquear amigo", "Desbloquear a un jugador"));
        mainMenu.setItem(20, createMenuItem(Material.CHEST, "Enviar Regalo", "Envía un ítem a un amigo"));
        mainMenu.setItem(24, createMenuItem(Material.BOOK, "Lista de Amigos", "Ver y gestionar tu lista de amigos"));
        fillInventoryWithPlaceholder(mainMenu);
    }

    private ItemStack createMenuItem(Material material, String name, String lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(Collections.singletonList(lore));
            item.setItemMeta(meta);
        }
        return item;
    }

    private void fillInventoryWithPlaceholder(Inventory inventory
    ) {
        ItemStack filler = createMenuItem(Material.GRAY_STAINED_GLASS_PANE, " ", " ");
        for (int i = 0; i < inventory.getSize(); i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, filler);
            }
        }
    }

    public void openMainMenu(Player player) {
        player.openInventory(mainMenu);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        String inventoryTitle = event.getView().getTitle();

        // Manejo del menú principal
        if (MAIN_MENU_TITLE.equals(inventoryTitle)) {
            event.setCancelled(true); // Cancela el evento para evitar que se muevan los items

            ItemStack clickedItem = event.getCurrentItem();
            if (clickedItem == null || !clickedItem.hasItemMeta() || clickedItem.getType().isAir()) {
                return; // Si no hay un item válido, no hacemos nada
            }

            // Maneja el clic en el menú
            handleMainMenuClick(player, clickedItem.getItemMeta().getDisplayName());
        }

        // Manejo del menú de la lista de amigos
        if (event.getView().getTitle().startsWith("Lista de Amigos")) {
            event.setCancelled(true); // Cancela el evento para evitar que se muevan los items
        }
    }

    private void handleMainMenuClick(Player player, String itemName) {
        switch (itemName) {
            case "Añadir amigo":
                initiatePendingAction(player, PendingAction.ADD_FRIEND, "Escribe en el chat el nombre del jugador que quieres añadir como amigo.");
                break;
            case "Eliminar amigo":
                initiatePendingAction(player, PendingAction.REMOVE_FRIEND, "Escribe en el chat el nombre del jugador que quieres eliminar de tus amigos.");
                break;
            case "Bloquear amigo":
                initiatePendingAction(player, PendingAction.BLOCK_FRIEND, "Escribe en el chat el nombre del jugador que quieres bloquear.");
                break;
            case "Desbloquear amigo":
                initiatePendingAction(player, PendingAction.UNBLOCK_FRIEND, "Escribe en el chat el nombre del jugador que quieres desbloquear.");
                break;
            case "Enviar Regalo":
                initiatePendingAction(player, PendingAction.SEND_GIFT, "Sostén el ítem que deseas enviar y escribe en el chat el nombre del jugador.");
                break;
            case "Lista de Amigos":
                openFriendsListMenu(player, 0);
                break;
            default:
                player.sendMessage("Opción no reconocida.");
                break;
        }
    }

    private void initiatePendingAction(Player player, PendingAction action, String message) {
        pendingActions.put(player, action);
        player.sendMessage(message);
        player.closeInventory();
    }

    public void openFriendsListMenu(Player player, int page) {
        Inventory friendsList = Bukkit.createInventory(null, 27, "Lista de Amigos - Página " + (page + 1));
        List<String> playerFriends = friends.getOrDefault(player.getName(), new ArrayList<>());

        int startIndex = page * 18;
        int endIndex = Math.min(startIndex + 18, playerFriends.size());

        for (int i = startIndex; i < endIndex; i++) {
            String friendName = playerFriends.get(i);
            friendsList.addItem(createMenuItem(Material.PLAYER_HEAD, friendName, "Haz clic para interactuar con " + friendName));
        }

        fillInventoryWithPlaceholder(friendsList);
        player.openInventory(friendsList);
    }

    private void loadData() {
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            try {
                getDataFolder().mkdirs();
                dataFile.createNewFile();
            } catch (IOException e) {
                getLogger().severe("Could not create data file: " + e.getMessage());
                return;
            }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);

        for (String playerName : dataConfig.getKeys(false)) {
            friends.put(playerName, dataConfig.getStringList(playerName + ".friends"));
            blockedPlayers.put(playerName, new HashSet<>(dataConfig.getStringList(playerName + ".blocked")));
        }

        for (String playerName : dataConfig.getKeys(false)) {
            List<String> requests = dataConfig.getStringList(playerName + ".friendRequests");
            if (!requests.isEmpty()) {
                friendRequests.put(playerName, requests);
            }
        }
    }

    private void saveData() {
        if (dataConfig == null) {
            getLogger().severe("dataConfig is null, cannot save.");
            return;
        }

        for (Map.Entry<String, List<String>> entry : friends.entrySet()) {
            String playerName = entry.getKey();
            dataConfig.set(playerName + ".friends", entry.getValue());
            dataConfig.set(playerName + ".blocked", new ArrayList<>(blockedPlayers.getOrDefault(playerName, new HashSet<>())));
        }

        for (Map.Entry<String, List<String>> entry : friendRequests.entrySet()) {
            String requesterName = entry.getKey();
            dataConfig.set(requesterName + ".friendRequests", entry.getValue());
        }

        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Could not save data file: " + e.getMessage());
        }
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player sender = event.getPlayer();
        String message = event.getMessage();

        // Convertir los códigos de color en el mensaje
        message = colorize(message);

        // Si el jugador está en una acción pendiente, maneja eso
        if (pendingActions.containsKey(sender)) {
            event.setCancelled(true);
            handlePendingAction(sender, message);
            pendingActions.remove(sender);
            return;
        }

        // Obtener todos los jugadores en el servidor excepto el remitente
        for (Player recipient : Bukkit.getOnlinePlayers()) {
            if (recipient == sender) {
                continue;
            }

            // Verificar si el destinatario ha bloqueado al remitente
            if (blockedPlayers.getOrDefault(recipient.getName(), new HashSet<>()).contains(sender.getName())) {
                // Si el destinatario ha bloqueado al remitente, no enviamos el mensaje a él
                event.getRecipients().remove(recipient);
            }
        }

        // Establecer el mensaje modificado para que se envíe
        event.setMessage(message);
    }

    private void handlePendingAction(Player player, String message) {
        PendingAction action = pendingActions.get(player);
        switch (action) {
            case ADD_FRIEND:
                addFriend(player, message);
                break;
            case REMOVE_FRIEND:
                removeFriend(player, message);
                break;
            case BLOCK_FRIEND:
                blockFriend(player, message);
                break;
            case UNBLOCK_FRIEND:
                unblockFriend(player, message);
                break;
            case SEND_GIFT:
                sendGift(player, message);
                break;
        }
    }

    private void addFriend(Player player, String friendName) {
        Player friend = Bukkit.getPlayer(friendName);
        if (friend == null || !friend.isOnline()) {
            player.sendMessage("El jugador no está en línea.");
            return;
        }

        if (player.getName().equals(friendName)) {
            player.sendMessage("No puedes agregar a tu propio nombre como amigo.");
            return;
        }

        List<String> playerFriends = friends.getOrDefault(player.getName(), new ArrayList<>());
        if (playerFriends.contains(friendName)) {
            player.sendMessage(friendName + " ya está en tu lista de amigos.");
            return;
        }

        List<String> requests = friendRequests.getOrDefault(friendName, new ArrayList<>());
        if (requests.contains(player.getName())) {
            player.sendMessage("Ya has enviado una solicitud de amistad a " + friendName + ".");
            return;
        }

        requests.add(player.getName());
        friendRequests.put(friendName, requests);
        player.sendMessage("Has enviado una solicitud de amistad a " + friendName + ".");
        friend.sendMessage(player.getName() + " te ha enviado una solicitud de amistad. Usa /aceptar " + player.getName() + " para aceptarla.");

        saveData();
    }

    private void removeFriend(Player player, String friendName) {
        List<String> playerFriends = friends.getOrDefault(player.getName(), new ArrayList<>());
        if (!playerFriends.contains(friendName)) {
            player.sendMessage("No tienes a " + friendName + " en tu lista de amigos.");
            return;
        }

        playerFriends.remove(friendName);
        friends.put(player.getName(), playerFriends);
        player.sendMessage("¡Has eliminado a " + friendName + " de tu lista de amigos!");

        Player friend = Bukkit.getPlayer(friendName);
        if (friend != null && friend.isOnline()) {
            friend.sendMessage(player.getName() + " te ha eliminado de su lista de amigos.");
        }

        saveData();
    }

    private void blockFriend(Player player, String friendName) {
        Set<String> playerBlocked = blockedPlayers.getOrDefault(player.getName(), new HashSet<>());
        if (playerBlocked.contains(friendName)) {
            player.sendMessage("Ya tienes bloqueado a " + friendName + ".");
            return;
        }

        playerBlocked.add(friendName);
        blockedPlayers.put(player.getName(), playerBlocked);
        player.sendMessage("¡Has bloqueado a " + friendName + "!");

        Player friend = Bukkit.getPlayer(friendName);
        if (friend != null && friend.isOnline()) {
            friend.sendMessage(player.getName() + " te ha bloqueado.");
        }

        saveData();
    }

    private void unblockFriend(Player player, String friendName) {
        Set<String> playerBlocked = blockedPlayers.getOrDefault(player.getName(), new HashSet<>());
        if (!playerBlocked.contains(friendName)) {
            player.sendMessage("No tienes bloqueado a " + friendName + ".");
            return;
        }

        playerBlocked.remove(friendName);
        blockedPlayers.put(player.getName(), playerBlocked);
        player.sendMessage("¡Has desbloqueado a " + friendName + "!");

        Player friend = Bukkit.getPlayer(friendName);
        if (friend != null && friend.isOnline()) {
            friend.sendMessage(player.getName() + " te ha desbloqueado.");
        }

        saveData();
    }

    private void sendGift(Player sender, String recipientName) {
        Player recipient = Bukkit.getPlayer(recipientName);
        if (recipient == null || !recipient.isOnline()) {
            sender.sendMessage("El jugador no está en línea.");
            return;
        }

        ItemStack giftItem = sender.getInventory().getItemInMainHand();
        if (giftItem == null || giftItem.getType() == Material.AIR) {
            sender.sendMessage("Debes sostener un ítem en la mano.");
            return;
        }

        recipient.getInventory().addItem(giftItem);
        sender.sendMessage("¡Has enviado un regalo a " + recipientName + "!");
        recipient.sendMessage("¡Has recibido un regalo de " + sender.getName() + "!");

        sender.getInventory().setItemInMainHand(null);
        saveData();
    }

    private void showHelp(Player player) {
        player.sendMessage("§6=== Comandos de Panas===");
        player.sendMessage("§7/panas §8- Abre el menú de gestión de amigos.");
        player.sendMessage("§7/helppanas§8- Muestra este menu de ayuda.");
        player.sendMessage("§7/aceptar + nick §8- Para Rechazar Solicitudes ");
        player.sendMessage("§7/rechazar + nick §8- Para Rechazar Solicitudes");
        player.sendMessage("§6=== Panas Commands ===");
        player.sendMessage("§7/panas §8- Open the Friends Management menu.");
        player.sendMessage("§7/helppanas§8- Show this help menu.");
        player.sendMessage("§7/aceptar + nick §8- To Accept Requests ");
        player.sendMessage("§7/rechazar + nick §8- To Decline Applications");
    }

    private void showFriendsList(Player player) {
        List<String> playerFriends = friends.getOrDefault(player.getName(), new ArrayList<>());
        if (playerFriends.isEmpty()) {
            player.sendMessage("No tienes amigos en tu lista.");
        } else {
            player.sendMessage("Tus amigos: " + String.join(", ", playerFriends));
        }
    }
    public static String colorize(String message) {
        return message.replaceAll("&", "§");
    }
}

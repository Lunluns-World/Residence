# Residence

Residence is different from most protection plugins in that it allows users to protect their own homes without needing to bother the server administrator.

It supports a variety of protection types, including preventing movement through protected areas. All settings can be configured by the landowner, with limits defined by the admin.

---

## 📜 Credit

Original plugin created by **bekvon** and later made premium with their permission to further development.

---

## 📦 Source and Downloads

- **GitHub Source Code:** https://github.com/Zrips/Residence  
- **Premium Release on SpigotMC:** https://www.spigotmc.org/resources/11480/  
- **Precompiled Builds:** https://zrips.net/Residence/  

---

## ⚙️ Maven Setup

Residence is available via JitPack (https://jitpack.io/), making integration into your Maven project simple.

### Add the repository to your pom.xml:
```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>
```

### Add the dependency:
```xml
<dependency>
    <groupId>com.github.Zrips</groupId>
    <artifactId>Residence</artifactId>
    <version>6.0.0.1</version>
    <scope>provided</scope>
</dependency>
```

This will include Residence in your project, allowing you to use its API and functionality directly.

---

## Database storage and cross-server residences

Residence uses YAML by default. For a shared database, set `Storage.Type` to `MARIADB`, `MYSQL`, or `POSTGRESQL` and configure `Host`, `Port`, `Database`, `Username`, and `Password`. Use port 5432 for a default PostgreSQL installation. A new server record can load the existing YAML save on its first start. Back up `plugins/Residence/Save` before changing the storage backend.

Enable `CrossServer.Enabled` on each backend, point all backends at the same database and table prefix, and give each one its own `CrossServer.ServerName`. The name must match the proxy's backend registration. For Velocity, enable its BungeeCord plugin messaging compatibility. [Velocity plugin messaging](https://docs.papermc.io/velocity/dev/plugin-messaging/#bungeecord-channel-compatibility)

- `/res list` includes the player's residences on other backends.
- `/res tp <server>:<residence>` sends the player to the destination backend. That backend applies its local Residence teleport rules.
- `/res gui` opens the Residence browser, with server filtering, owned residences, pins, likes, and local management actions. [ResidenceList](https://github.com/ArtformGames/ResidenceList) inspired the browser workflow.
- `CrossServer.GlobalResidenceLimit` shares the player residence limit across the configured backends.
- `CrossServer.ServerDisplayNames` maps backend server IDs (e.g. `server-1: "&aSurvival 1"`) to friendly display names shown in `/res gui`, `/res list`, and teleport messages (hot-reloadable via `/res reload config`).

## Import and export

Run the following commands from the server console.

| Command | Result |
| --- | --- |
| `resadmin migrate griefprevention preview [ClaimDataPath]` | Read GriefPrevention claim files and show the number of claims available to import. |
| `resadmin migrate griefprevention apply [ClaimDataPath]` | Import claim bounds, owner UUIDs, subdivisions, and supported trust levels. Review GP permission groups, delegation trust, and claim-block balances separately. |
| `resadmin migrate lands preview [JsonDirectory]` | Read Lands through its API when the plugin is loaded. A directory argument selects compatible JSON exports. |
| `resadmin migrate lands apply [JsonDirectory]` | Import supported chunk footprints and ownership. Review Lands trust roles before switching protection. The live importer skips lands with subareas. |
| `resadmin migrate db2yaml` | Export the active server's SQL save under `plugins/Residence/Exports/sql-*`. |
| `resadmin migrate yaml2db apply` | Write the loaded legacy data to a database that has no save for this server. |

---

## 📚 Documentation & Resources

- **Release Thread (SpigotMC):** https://www.spigotmc.org/resources/11480/  
- **Changelogs:** https://www.spigotmc.org/resources/11480/updates  
- **Official Documentation & Wiki:** https://www.zrips.net/residence/

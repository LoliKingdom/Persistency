package zone.rong.persistency;

import com.google.common.eventbus.EventBus;
import com.google.common.io.Files;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.Launch;
import net.minecraftforge.common.ForgeVersion;
import net.minecraftforge.fml.common.DummyModContainer;
import net.minecraftforge.fml.common.LoadController;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModMetadata;
import net.minecraftforge.fml.common.event.FMLLoadEvent;
import net.minecraftforge.fml.relauncher.FMLLaunchHandler;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import net.minecraftforge.fml.relauncher.Side;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import javax.annotation.Nullable;
import java.io.*;
import java.util.ListIterator;
import java.util.Map;

/**
 * Base class. There is no particular code that needs to be exposed through an API.
 * Simply depend on this softly (no need to put into buildpath).
 *
 * Persistency will set itself up after mods have been indexed and "activated" (set to active).
 *
 * All data related to Persistency will be exposed via {@link Launch#blackboard}.
 *
 * The main caches folder will be queried via {@link Launch#blackboard} with the key: "CachesFolderFile".
 *  - It is recommended that all caches use this folder, mod ids can be used as a nested directory to differentiate.
 *
 * The mods cache file will be queried via {@link Launch#blackboard} with the key: "ModsCacheFile".
 *  - It is recommended that you do not read/write from/to this File whatsoever. But it is here to show the filepath.
 *  - It could be that this file does not exist in the Filesystem when queried, meaning the file hadn't yet exist or needed to be refreshed.
 *  - This file should only be written onto disk when the client doesn't crash/exit during load.
 *
 * The temporary mods cache file will be queried via {@link Launch#blackboard} with the key: "TempModsCacheFile".
 *  - It is recommended that you do not read/write from/to this File whatsoever. But it is here to show the (temp) filepath.
 *  - It is here to ensure that ModsCacheFile isn't written directly onto disk when the client crashes/exits during load.
 *  - If this file exists, ModsCacheFile won't exist. (new in 1.1.0)
 *  - This file will be deletedOnExit.
 *
 * This shows whether or not the current load is consistent (structurally similar) with the last load,
 * will be stored as a boolean via {@link Launch#blackboard} with the key: "ConsistentLoad".
 *  - Here is an easy boolean you could query to see if current load is near identical with the last one.
 *  - The comparisons are based on mod names + mod versions.
 *  - Configs are very hard to compare, hence it is not taken into account. Beware.
 */
@SuppressWarnings("unused")
@IFMLLoadingPlugin.Name("Persistency")
@IFMLLoadingPlugin.MCVersion(ForgeVersion.mcVersion)
public class Persistency implements IFMLLoadingPlugin {

    public static final Logger LOGGER = LogManager.getLogger("Persistency");

    static {
        LOGGER.info("Loading Persistency CoreMod");
    }

    @Override
    public String[] getASMTransformerClass() {
        return FMLLaunchHandler.side() == Side.CLIENT ? new String[] { "zone.rong.persistency.Persistency$ClientTransformer" } : new String[] { "zone.rong.persistency.Persistency$ServerTransformer" };
    }

    @Override
    public String getModContainerClass() {
        return "zone.rong.persistency.Persistency$Container";
    }

    @Nullable
    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) { }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }

    public static class Hooks {

        static {
            LOGGER.info("Initializing Persistency Hooks.");
        }

        public static void afterBuildingModList() {
            LOGGER.info("Querying last minecraft load to see if most things are identical...");
            File cachesFolder = new File(Launch.minecraftHome, "caches");
            cachesFolder.mkdirs();
            Launch.blackboard.put("CachesFolderFile", cachesFolder);
            File modsCache = new File(cachesFolder, "mods.bin");
            Launch.blackboard.put("ModsCacheFile", modsCache);
            Map<String, String> currentModList = Loader.instance().getActiveModList().stream().collect(Object2ObjectOpenHashMap::new, (map, mod) -> map.put(mod.getModId(), mod.getVersion()), Map::putAll);
            if (modsCache.exists()) {
                try {
                    FileInputStream fileInputStream = new FileInputStream(modsCache);
                    ObjectInputStream objectInputStream = new ObjectInputStream(fileInputStream);
                    Map<String, String> previousModList = (Map<String, String>) objectInputStream.readObject();
                    objectInputStream.close();
                    if (currentModList.equals(previousModList)) {
                        Launch.blackboard.put("ConsistentLoad", true);
                        LOGGER.info("Mod list is the same as last launch.");
                        return;
                    }
                } catch (ClassNotFoundException | IOException e) {
                    e.printStackTrace();
                }
            }
            LOGGER.info("Mod list isn't the same as last launch. Refreshing cache.");
            Launch.blackboard.put("ConsistentLoad", false);
            File tempModsCache;
            try {
                tempModsCache = File.createTempFile("modscache", null);
                tempModsCache.deleteOnExit();
                Launch.blackboard.put("TempModsCacheFile", tempModsCache);
                FileOutputStream fileOutputStream = new FileOutputStream(tempModsCache);
                ObjectOutputStream objectOutputStream = new ObjectOutputStream(fileOutputStream);
                objectOutputStream.writeObject(currentModList);
                objectOutputStream.flush();
                objectOutputStream.close();
                LOGGER.info("Creating temp Mod list cache.");
            } catch (IOException e) {
                LOGGER.error("Could not create Persistency's temporary mod list cache!");
                e.printStackTrace();
            }
        }

        public static void afterFinishMinecraftLoading() {
            try {
                if (!(Boolean) Launch.blackboard.get("ConsistentLoad")) {
                    Files.move((File) Launch.blackboard.remove("TempModsCacheFile"), (File) Launch.blackboard.get("ModsCacheFile"));
                    LOGGER.info("Cementing Mod list cache. As nothing has crashed before the game has loaded");
                }
            } catch (IOException e) {
                e.printStackTrace();
                LOGGER.info("Could not persist temporary mod list cache!");
            }
        }

    }

    public static class ServerTransformer extends Transformer {

        @Override
        public byte[] transform(String name, String transformedName, byte[] bytes) {
            if (name.equals("net.minecraftforge.fml.server.FMLServerHandler")) {
                return injectCallAtLastReturn(bytes, "finishServerLoading", new MethodInsnNode(Opcodes.INVOKESTATIC, "zone/rong/persistency/Persistency$Hooks", "afterFinishMinecraftLoading", "()V", false));
            }
            return super.transform(name, transformedName, bytes);
        }

    }

    public static class ClientTransformer extends Transformer {

        @Override
        public byte[] transform(String name, String transformedName, byte[] bytes) {
            if (name.equals("net.minecraftforge.fml.client.FMLClientHandler")) {
                return injectCallAtLastReturn(bytes, "finishMinecraftLoading", new MethodInsnNode(Opcodes.INVOKESTATIC, "zone/rong/persistency/Persistency$Hooks", "afterFinishMinecraftLoading", "()V", false));
            }
            return super.transform(name, transformedName, bytes);
        }

    }

    private static abstract class Transformer implements IClassTransformer {

        @Override
        public byte[] transform(String name, String transformedName, byte[] bytes) {
            if (name.equals("net.minecraftforge.fml.common.LoadController")) {
                return injectCallAtLastReturn(bytes, "buildModList", new MethodInsnNode(Opcodes.INVOKESTATIC, "zone/rong/persistency/Persistency$Hooks", "afterBuildingModList", "()V", false));
            }
            return bytes;
        }

    }

    private static byte[] injectCallAtLastReturn(byte[] bytes, String methodToInjectInto, MethodInsnNode methodNode) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode classNode = new ClassNode();
        reader.accept(classNode, 0);
        for (MethodNode method : classNode.methods) {
            if (method.name.equals(methodToInjectInto)) {
                AbstractInsnNode lastReturnNode = null;
                ListIterator<AbstractInsnNode> iter = method.instructions.iterator();
                while (iter.hasNext()) {
                    AbstractInsnNode instruction = iter.next();
                    if (instruction.getOpcode() == Opcodes.RETURN) {
                        lastReturnNode = instruction;
                    }
                }
                if (lastReturnNode != null) {
                    method.instructions.insertBefore(lastReturnNode, methodNode);
                } else {
                    LOGGER.error("Could not transform {} properly to insert hook!", classNode.name);
                }
            }
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        classNode.accept(writer);
        return writer.toByteArray();
    }

    public static class Container extends DummyModContainer {

        public Container() {
            super(new ModMetadata());
            ModMetadata meta = this.getMetadata();
            meta.modId = "persistency";
            meta.name = "Persistency";
            meta.description = "A Coremod + Library aiding persistency of data in minecraft modding and caching.";
            meta.version = "1.1.0";
            meta.logoFile = "/icon.png";
            meta.authorList.add("Rongmario");
        }

        @Override
        public boolean registerBus(EventBus bus, LoadController controller) {
            bus.register(this);
            return true;
        }

    }

}

package fr.dynamx.common.slopes;

import fr.aym.acslib.utils.DeserializedData;
import fr.aym.acslib.utils.nbtserializer.ISerializable;
import fr.aym.acslib.utils.nbtserializer.NBTSerializer;
import net.minecraft.block.Block;
import net.minecraft.block.BlockSlab;
import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

public class SlopeBuildingConfig implements ISerializable {
    private int version;
    private EnumFacing facing = EnumFacing.DOWN;
    private int diagDir;
    private boolean enableSlabs;
    private final List<Block> blackList = new ArrayList<>();

    public SlopeBuildingConfig() {
    }

    public SlopeBuildingConfig(NBTTagCompound from) {
        if (!from.isEmpty()) {
            try {
                NBTSerializer.deserialize(from, this);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void setEnableSlabs(boolean enableSlabs) {
        this.enableSlabs = enableSlabs;
        version++;
    }

    public boolean isEnableSlabs() {
        return enableSlabs;
    }

    public void refresh() {
        version++;
    }

    public int getConfigVersion() {
        return version;
    }

    public int getDiagDir() {
        if (diagDir != -1 && diagDir != 1)
            diagDir = 1;
        return diagDir;
    }

    public void setDiagDir(int diagDir) {
        this.diagDir = diagDir;
        version++;
    }

    public void setFacing(EnumFacing facing) {
        this.facing = facing;
        version++;
    }

    public EnumFacing getFacing() {
        return facing;
    }

    public List<Block> getBlackList() {
        return blackList;
    }

    @Override
    public int getVersion() {
        return 2;
    }

    @Override
    public Object[] getObjectsToSave() {
        List<String> auBlack = new ArrayList<>();
        for (Block black : blackList) {
            auBlack.add(black.getRegistryName().toString());
        }
        return new Object[]{version, facing.ordinal(), diagDir, enableSlabs, auBlack};
    }

    @Override
    public void populateWithSavedObjects(DeserializedData objects) {
        version = objects.next();
        facing = EnumFacing.byIndex(objects.next());
        diagDir = objects.next();
        enableSlabs = NBTSerializer.convert(objects.next());
        blackList.clear();
        List<String> auBlack = objects.next();
        for (String loc : auBlack) {
            blackList.add(Block.REGISTRY.getObject(new ResourceLocation(loc)));
        }
    }

    public boolean isValidBlock(IBlockState block) {
        if (blackList.contains(block.getBlock()))
            return false;
        return enableSlabs || !(block.getBlock() instanceof BlockSlab && !(((BlockSlab) block.getBlock()).isDouble()/* || enableFullSlabs*/));
    }

    public NBTTagCompound serialize() {
        return (NBTTagCompound) NBTSerializer.serialize(this);
    }
}

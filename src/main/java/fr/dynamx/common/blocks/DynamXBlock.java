package fr.dynamx.common.blocks;

import com.jme3.math.Vector3f;
import fr.dynamx.api.blocks.IBlockEntityModule;
import fr.dynamx.api.contentpack.object.IDynamXItem;
import fr.dynamx.api.contentpack.object.part.InteractivePart;
import fr.dynamx.api.contentpack.object.render.Enum3DRenderLocation;
import fr.dynamx.api.contentpack.object.render.IModelPackObject;
import fr.dynamx.api.contentpack.object.render.IResourcesOwner;
import fr.dynamx.api.contentpack.object.subinfo.ISubInfoTypeOwner;
import fr.dynamx.api.events.DynamXBlockEvent;
import fr.dynamx.common.capability.DynamXChunkData;
import fr.dynamx.common.capability.DynamXChunkDataProvider;
import fr.dynamx.common.contentpack.DynamXObjectLoaders;
import fr.dynamx.common.contentpack.type.objects.BlockObject;
import fr.dynamx.common.contentpack.type.objects.PropObject;
import fr.dynamx.common.entities.IDynamXObject;
import fr.dynamx.common.items.DynamXItemRegistry;
import fr.dynamx.utils.DynamXConstants;
import fr.dynamx.utils.RegistryNameSetter;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.block.properties.PropertyInteger;
import net.minecraft.block.state.BlockStateContainer;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.*;
import net.minecraft.util.math.*;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;

public class DynamXBlock<T extends BlockObject<?>> extends Block implements IDynamXItem<T>, IResourcesOwner {

    public static final PropertyInteger METADATA = PropertyInteger.create("metadata", 0, 15);

    /**
     * Cache
     */
    public T blockObjectInfo;

    public final int textureNum;

    private final boolean isDxModel;

    /**
     * Internally used by DynamX, don't use this constructor <br>
     * Creates a {@link Block} from a loaded {@link BlockObject}
     *
     * @param blockObjectInfo a BlockObject loaded by the content pack system
     */
    public DynamXBlock(T blockObjectInfo) {
        super(blockObjectInfo.getMaterial());
        setInfo(blockObjectInfo);
        setCreativeTab(blockObjectInfo.getCreativeTab(DynamXItemRegistry.objectTab));
        textureNum = Math.min(16, blockObjectInfo.getMaxVariantId());
        isDxModel = blockObjectInfo.isDxModel();

        initBlock(DynamXConstants.ID);
        setTranslationKey(DynamXConstants.ID + "." + blockObjectInfo.getFullName().toLowerCase());
    }

    /**
     * Use this constructor to create a custom block having the same functionalities as pack blocks. A second constructor allows you to also add a prop with this blocks. <br>
     * You can customise block properties using this.blockObjectInfo. <br> <br>
     * NOTE : Registry name and translation key are automatically set and the block is automatically registered into Forge by DynamX,
     * but don't forget to set a creative tab ! <br><br>
     *
     * <strong>NOTE : Should be called during addons initialization</strong>
     *
     * @param material  The block material
     * @param modid     The mod owning this block, used to register the block
     * @param blockName The name of the block
     * @param model     The obj model of the block
     */
    public DynamXBlock(Material material, String modid, String blockName, ResourceLocation model) {
        this(material, modid, blockName, model, null);
    }

    /**
     * Use this constructor to create a custom block having the same functionalities as pack blocks. This constructor also adds a prop to the block, if the "propsName" parameter isn't null. <br>
     * You can customise block properties using this.blockObjectInfo. <br> <br>
     * NOTE : Registry name and translation key are automatically set and the block and prop are automatically registered into Forge by DynamX,
     * but don't forget to set a creative tab ! <br><br>
     *
     * <strong>NOTE : Should be called during addons initialization</strong>
     *
     * @param material  The block material
     * @param modid     The mod owning this block, used to register the block
     * @param blockName The name of the block
     * @param model     The obj model of the block
     * @param propsName The name of the props to create, can be null
     */
    public DynamXBlock(Material material, String modid, String blockName, ResourceLocation model, String propsName) {
        super(material);
        if (modid.contains("builtin_mod_")) { //Backward-compatibility
            blockObjectInfo = (T) DynamXObjectLoaders.BLOCKS.addBuiltinObject(this, modid, blockName);
            modid = modid.replace("builtin_mod_", "");
        } else {
            blockObjectInfo = (T) DynamXObjectLoaders.BLOCKS.addBuiltinObject(this, "dynx." + modid, blockName);
        }
        blockObjectInfo.setModel(model);
        blockObjectInfo.setDescription("Builtin " + modid + "'s block");
        textureNum = 1;
        isDxModel = blockObjectInfo.isDxModel();

        initBlock(modid);
        setTranslationKey(blockObjectInfo.getFullName().toLowerCase());

        if (propsName != null) {
            PropObject<?> prop = new PropObject<>((ISubInfoTypeOwner<BlockObject<?>>) getInfo(), propsName);
            prop.setEmptyMass(10);
            prop.setCenterOfMass(new Vector3f(0, 0, 0));
            DynamXObjectLoaders.PROPS.addBuiltinObject("dynx." + modid, prop);
            getInfo().setPropObject(prop);
            System.out.println("PROPS ADDED ?");
        }
    }

    protected void initBlock(String modid) {
        setDefaultState(this.blockState.getBaseState().withProperty(METADATA, 0));
        RegistryNameSetter.setRegistryName(this, modid, blockObjectInfo.getFullName().toLowerCase());
        DynamXItemRegistry.registerItemBlock(this);
    }

    @Override
    public ItemStack getPickBlock(IBlockState state, RayTraceResult target, World world, BlockPos pos, EntityPlayer player) {
        ItemStack pickBlock = super.getPickBlock(state, target, world, pos, player);
        pickBlock.setItemDamage(state.getValue(METADATA));
        return pickBlock;
    }

    @Override
    public IBlockState getStateFromMeta(int meta) {
        return this.getDefaultState().withProperty(METADATA, meta);
    }

    @Override
    public int getMetaFromState(IBlockState state) {
        return state.getValue(METADATA);
    }

    @Override
    protected BlockStateContainer createBlockState() {
        return new BlockStateContainer(this, METADATA);
    }

    @SideOnly(Side.CLIENT)
    public void addInformation(ItemStack stack, @Nullable World worldIn, List<String> tooltip, ITooltipFlag flagIn) {
        tooltip.add("Description: " + getInfo().getDescription());
        tooltip.add("Pack: " + getInfo().getPackName());
        if (stack.getMetadata() > 0 && textureNum > 1) {
            tooltip.add("Texture: " + getInfo().getMainObjectVariantName((byte) stack.getMetadata()));
        }
    }

    @Override
    public boolean onBlockActivated(World worldIn, BlockPos pos, IBlockState state, EntityPlayer playerIn, EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        if (worldIn.isRemote && !playerIn.isSneaking()) {
            return false;
        }
        TileEntity te = worldIn.getTileEntity(pos);
        if (te instanceof TEDynamXBlock) {
            //TODO ADD INTERACT EVENTS
            //If we clicked a part, try to interact with it.
            InteractivePart<IDynamXObject, ?> hitPart = (InteractivePart<IDynamXObject, ?>) ((TEDynamXBlock) te).getHitPart(playerIn);
            if (hitPart == null || !hitPart.canInteract((IDynamXObject) te, playerIn)) {
                // If there's no hit/can't interact, try to open the customization gui
                if (playerIn.isSneaking() && playerIn.capabilities.isCreativeMode) {
                    if (worldIn.isRemote && isDxModel)
                        ((TEDynamXBlock) te).openConfigGui();
                    return true;
                    /*
                    //TODO animations
                    if (te instanceof TEDynamXBlock && hand.equals(EnumHand.MAIN_HAND)) {
                        DxAnimator animator = ((TEDynamXBlock) te).getAnimator();
                        if (playerIn.isSneaking()) {
                            DxModelRenderer model = DynamXContext.getDxModelRegistry().getModel(blockObjectInfo.getModel());
                            animator.playNextAnimation();
                            //te.getAnimator().addAnimation("Reset");
                            return true;
                        }
                        animator.setBlendPose(DxAnimator.EnumBlendPose.START_END);
                        animator.addAnimation("Run1", DxAnimation.EnumAnimType.START_END);
                    }*/
                }
                return false;
            }
            // only interact on server side
            return worldIn.isRemote || hitPart.interact((IDynamXObject) te, playerIn);
        }
        return false;
    }

    @Override
    public boolean hasTileEntity(IBlockState state) {
        return isDxModel;
    }

    @Nullable
    @Override
    public TileEntity createTileEntity(World world, IBlockState state) {
        if (isDxModel) {
            DynamXBlockEvent.CreateTileEntity event = new DynamXBlockEvent.CreateTileEntity(world.isRemote ? Side.CLIENT : Side.SERVER, this, world, new TEDynamXBlock(blockObjectInfo));
            MinecraftForge.EVENT_BUS.post(event);
            return event.getTileEntity();
        }
        return null;
    }

    @Override
    public void onBlockPlacedBy(World worldIn, BlockPos pos, IBlockState state, EntityLivingBase placer, ItemStack stack) {
        super.onBlockPlacedBy(worldIn, pos, state, placer, stack);
        int rotation = MathHelper.floor((placer.rotationYaw * 16.0F / 360.0F) + 0.5D) & 0xF;
        if (isDxModel()) {
            TileEntity tileentity = worldIn.getTileEntity(pos);
            if (tileentity instanceof TEDynamXBlock) {
                TEDynamXBlock teDynamXBlock = (TEDynamXBlock) tileentity;
                teDynamXBlock.setRotation(rotation);
            }
        } else {
            worldIn.setBlockState(pos, state.withProperty(DynamXBlock.METADATA, rotation));
        }
    }

    @Override //Handled by the RotatedCollisionHandler
    public void addCollisionBoxToList(IBlockState state, World world, BlockPos pos, AxisAlignedBB entityBox, List<AxisAlignedBB> collidingBoxes, @Nullable Entity entityIn, boolean isActualState) {
    }

    @Override
    public AxisAlignedBB getBoundingBox(IBlockState state, IBlockAccess source, BlockPos pos) {
        return getComputedBB(source, pos);
    }

    @Override
    public AxisAlignedBB getCollisionBoundingBox(IBlockState blockState, IBlockAccess worldIn, BlockPos pos) {
        return FULL_BLOCK_AABB;
    }

    @Override
    public void breakBlock(World worldIn, BlockPos pos, IBlockState state) {
        TEDynamXBlock te = (TEDynamXBlock) worldIn.getTileEntity(pos);
        if (te != null) {
            te.removeChunkCollisions();
            te.getModules().forEach(IBlockEntityModule::onBlockBreak);
        } else {
            // This should not happen, but, just in case fallback and clear the block's chunk
            DynamXChunkData data = worldIn.getChunk(pos).getCapability(DynamXChunkDataProvider.DYNAMX_CHUNK_DATA_CAPABILITY, null);
            data.getBlocksAABB().remove(pos);
        }
        super.breakBlock(worldIn, pos, state);
    }

    @Override
    public void getDrops(NonNullList<ItemStack> drops, IBlockAccess world, BlockPos pos, IBlockState state, int fortune) {
        super.getDrops(drops, world, pos, state, fortune);
        TEDynamXBlock te = (TEDynamXBlock) world.getTileEntity(pos);
        if (te != null) {
            te.getModules().forEach(module -> module.getBlockDrops(drops, world, pos, state, fortune));
        }
    }

    @Nullable
    @Override
    public RayTraceResult collisionRayTrace(IBlockState blockState, World worldIn, BlockPos pos, Vec3d start, Vec3d end) {
        return this.rayTrace(pos, start, end, getComputedBB(worldIn, pos));
    }

    public AxisAlignedBB getComputedBB(IBlockAccess world, BlockPos pos) {
        TileEntity tileEntity = world.getTileEntity(pos);
        if (tileEntity instanceof TEDynamXBlock) {
            return ((TEDynamXBlock) tileEntity).computeBoundingBox();
        } //FIXME DO FOR NO-OBJ BLOCKS
        return FULL_BLOCK_AABB;
    }

    @Override
    public T getInfo() {
        return blockObjectInfo;
    }

    @Override
    public void setInfo(T info) {
        blockObjectInfo = info;
        setLightLevel(blockObjectInfo.getLightLevel());
        setHardness(blockObjectInfo.getBlockHardness());
        setResistance(blockObjectInfo.getBlockResistance());
        setSoundType(blockObjectInfo.getSoundType());
        if (!StringUtils.isNullOrEmpty(blockObjectInfo.getHarvestTool())) {
            setHarvestLevel(blockObjectInfo.getHarvestTool(), blockObjectInfo.getHarvestLevel());
        }
    }

    @Override
    public boolean createJson() {
        return IResourcesOwner.super.createJson() || blockObjectInfo.get3DItemRenderLocation() != Enum3DRenderLocation.ALL;
    }

    @Override
    public String getJsonName(int meta) {
        return getInfo().getName().toLowerCase();
    }

    @Override
    public IModelPackObject getDxModel() {
        return isDxModel ? getInfo() : null;
    }

    @Override
    public int getMaxMeta() {
        return textureNum;
    }

    @Override
    @Nonnull
    public EnumBlockRenderType getRenderType(@Nonnull final IBlockState state) {
        return isDxModel ? EnumBlockRenderType.INVISIBLE : EnumBlockRenderType.MODEL;
    }

    @Override
    public boolean isBlockNormalCube(IBlockState blockState) {
        return false;
    }

    @Override
    public boolean isFullCube(IBlockState state) {
        return false;
    }

    @Override
    public boolean isOpaqueCube(IBlockState state) {
        return false;
    }

    @Override
    public boolean isNormalCube(IBlockState state) {
        return false;
    }

    public boolean isDxModel() {
        return isDxModel;
    }
}

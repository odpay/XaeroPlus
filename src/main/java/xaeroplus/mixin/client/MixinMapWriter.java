package xaeroplus.mixin.client;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.minecraft.block.Block;
import net.minecraft.block.BlockAir;
import net.minecraft.block.BlockGlass;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.material.EnumPushReaction;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.DimensionType;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.map.MapProcessor;
import xaero.map.MapWriter;
import xaero.map.WorldMap;
import xaero.map.biome.BiomeColorCalculator;
import xaero.map.biome.WriterBiomeInfoSupplier;
import xaero.map.cache.BlockStateColorTypeCache;
import xaero.map.core.XaeroWorldMapCore;
import xaero.map.misc.Misc;
import xaero.map.region.*;
import xaeroplus.settings.XaeroPlusSettingRegistry;
import xaeroplus.util.ChunkUtils;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import static java.util.Objects.nonNull;

@Mixin(value = MapWriter.class, remap = false)
public abstract class MixinMapWriter {
    @Shadow
    private int playerChunkX;
    @Shadow
    private int playerChunkZ;
    @Shadow
    private OverlayBuilder overlayBuilder;
    @Shadow
    private int topH;
    @Shadow
    private int firstTransparentStateY;
    @Shadow
    private WriterBiomeInfoSupplier writerBiomeInfoSupplier;
    @Shadow
    private int[] biomeBuffer;
    @Shadow
    private BlockStateColorTypeCache colorTypeCache;
    @Shadow
    private MapProcessor mapProcessor;
    @Shadow
    @Final
    private BlockPos.MutableBlockPos mutableLocalPos;
    @Shadow
    @Final
    private BlockPos.MutableBlockPos mutableGlobalPos;
    @Shadow
    private int endTileChunkX;
    @Shadow
    private int endTileChunkZ;
    @Shadow
    private int startTileChunkX;
    @Shadow
    private int startTileChunkZ;
    @Shadow
    private long lastWriteTry;
    @Shadow
    private long lastWrite;
    @Shadow
    private int writeFreeSizeTiles;
    @Shadow
    private int writeFreeFullUpdateTargetTime;
    @Shadow
    private int workingFrameCount;
    @Shadow
    private long framesFreedTime = -1L;
    @Shadow
    public long writeFreeSinceLastWrite;
    @Final
    @Shadow
    private BlockPos.MutableBlockPos mutableBlockPos3;
    @Shadow
    private ArrayList<MapRegion> regionBuffer;
    @Shadow
    private int writingLayer;

    @Shadow
    public abstract boolean writeMap(
            World world,
            double playerX,
            double playerY,
            double playerZ,
            BiomeColorCalculator biomeColorCalculator,
            OverlayManager overlayManager,
            boolean loadChunks,
            boolean updateChunks,
            boolean ignoreHeightmaps,
            boolean flowers,
            boolean detailedDebug,
            BlockPos.MutableBlockPos mutableBlockPos3,
            int caveDepth
    );

    // insert our own limiter on new tiles being written but this one's keyed on the actual chunk
    // tile "writes" also include a lot of extra operations and lookups before any writing is actually done
    // when we remove existing limiters those extra operations add up to a lot of unnecessary cpu time
    private final Cache<Long, Long> tileUpdateCache = Caffeine.newBuilder()
            // I would usually expect even second long expiration here to be fine
            // but there are some operations that make repeat invocations actually required
            // perhaps another time ill rewrite those. Or make the cache lock more aware of when we don't have any new updates to write/load
            // there's still alot of performance and efficiency on the table to regain
            // but i think this is a good middle ground for now
            .maximumSize(10000)
            .expireAfterWrite(5L, TimeUnit.SECONDS)
            .<Long, Long>build();

    protected MixinMapWriter() {
    }

    @Shadow
    protected abstract boolean shouldOverlayCached(IBlockState state);

    @Shadow
    public abstract boolean hasVanillaColor(IBlockState state, World world, BlockPos pos);

    @Shadow
    public abstract boolean isInvisible(World world, IBlockState state, Block b, boolean flowers);

    @Shadow
    public abstract boolean isGlowing(IBlockState state);

    @Shadow
    protected abstract IBlockState unpackFramedBlocks(IBlockState original, World world, BlockPos globalPos);

    /**
     * @author Entropy5
     * @reason obsidian roof
     */
    @Inject(method = "shouldOverlay", at = @At("HEAD"), cancellable = true)
    public void shouldOverlay(IBlockState state, CallbackInfoReturnable<Boolean> cir) {
        if (!XaeroPlusSettingRegistry.transparentObsidianRoofSetting.getValue()) {
            return;
        }
        if (!(state.getBlock() instanceof BlockAir) && !(state.getBlock() instanceof BlockGlass) && state.getBlock().getRenderLayer() != BlockRenderLayer.TRANSLUCENT) {
            if (!(state.getBlock() instanceof BlockLiquid)) {
                cir.setReturnValue(false);
            } else {
                int lightOpacity = state.getLightOpacity(this.mapProcessor.getWorld(), BlockPos.ORIGIN);
                cir.setReturnValue(lightOpacity != 0); // deleted argument to render water under obsidian roof regardless of light opacity
            }
        } else {
            cir.setReturnValue(true);
        }
    }

    /**
     * @author Entropy5
     * @reason obsidian roof
     */
    @Inject(method = "loadPixel", at = @At("HEAD"), cancellable = true)
    public void loadPixel(World world, MapBlock pixel, MapBlock currentPixel,
                          Chunk bchunk, int insideX, int insideZ,
                          int highY, int lowY, boolean cave,
                          boolean fullCave,
                          int mappedHeight,
                          boolean canReuseBiomeColours,
                          boolean ignoreHeightmaps,
                          boolean flowers,
                          BlockPos.MutableBlockPos mutableBlockPos3,
                          CallbackInfo ci) {
        if (!XaeroPlusSettingRegistry.transparentObsidianRoofSetting.getValue()) {
            return;
        } else {
            ci.cancel();
        }
        pixel.prepareForWriting();
        this.overlayBuilder.startBuilding();
        boolean underair = !cave || fullCave;
        boolean shouldEnterGround = fullCave;
        IBlockState opaqueState = null;
        byte workingLight = -1;
        boolean worldHasSkyLight = world.provider.hasSkyLight();
        byte workingSkyLight = (byte)(worldHasSkyLight ? 15 : 0);
        this.topH = lowY;
        this.mutableGlobalPos.setPos((bchunk.getPos().x << 4) + insideX, lowY - 1, (bchunk.getPos().z << 4) + insideZ);
        boolean shouldExtendTillTheBottom = false;
        int transparentSkipY = 0;
        boolean columnRoofObsidian = false;

        // todo: figure out if this still works

        int h;
        IBlockState state;
        for (h = highY; h >= lowY; h = shouldExtendTillTheBottom ? transparentSkipY : h - 1) {
            this.mutableLocalPos.setPos(insideX, h, insideZ);
            this.mutableGlobalPos.setY(h);
            state = bchunk.getBlockState(this.mutableLocalPos);
            if (state == null) {
                state = Blocks.AIR.getDefaultState();
            }
            state = this.unpackFramedBlocks(state, world, this.mutableGlobalPos);
            Block b = state.getBlock();
            boolean roofObsidian = (h > 253 && b == Blocks.OBSIDIAN);
            if (roofObsidian && XaeroPlusSettingRegistry.transparentObsidianRoofDarkeningSetting.getValue() == 0) {
                continue;  // skip over obsidian roof completely
            }
            if (roofObsidian & !columnRoofObsidian) {
                columnRoofObsidian = true;
            }
            shouldExtendTillTheBottom = !shouldExtendTillTheBottom && !this.overlayBuilder.isEmpty() && this.firstTransparentStateY - h >= 5 && !columnRoofObsidian;
            if (shouldExtendTillTheBottom) {
                for (transparentSkipY = h - 1; transparentSkipY >= lowY; --transparentSkipY) {
                    IBlockState traceState = bchunk.getBlockState(mutableBlockPos3.setPos(insideX, transparentSkipY, insideZ));
                    if (traceState == null) { // should be impossible lol
                        traceState = Blocks.AIR.getDefaultState();
                    }
                    if (!this.shouldOverlayCached(traceState)) {
                        break;
                    }
                }
            }
            if (b instanceof BlockAir) {
                underair = true;
            } else if (underair && !this.isInvisible(world, state, b, flowers)) {
                if (!cave || !shouldEnterGround) {
                    this.mutableLocalPos.setY(Math.min(255, h + 1));
                    workingLight = (byte)bchunk.getLightFor(EnumSkyBlock.BLOCK, this.mutableLocalPos);
                    if (cave && workingLight < 15 && worldHasSkyLight) {
                        if (!ignoreHeightmaps && !fullCave && highY >= mappedHeight) {
                            workingSkyLight = 15;
                        } else {
                            workingSkyLight = (byte)bchunk.getLightFor(EnumSkyBlock.SKY, this.mutableLocalPos);
                        }
                    }
                    if (this.shouldOverlayCached(state) || roofObsidian) {
                        if (h > this.topH) {
                            this.topH = h;
                        }

                        byte overlayLight = workingLight;
                        if (this.overlayBuilder.isEmpty()) {
                            this.firstTransparentStateY = h;
                            if (cave && workingSkyLight > workingLight) {
                                overlayLight = workingSkyLight;
                            }
                        }

                        if (shouldExtendTillTheBottom) {
                            this.overlayBuilder.getCurrentOverlay().increaseOpacity(Misc.getStateById(this.overlayBuilder.getCurrentOverlay().getState()).getLightOpacity(world, this.mutableGlobalPos) * (h - transparentSkipY));
                        } else {
                            this.writerBiomeInfoSupplier.set(currentPixel, canReuseBiomeColours);
                            int stateId = Block.getStateId(state);
                            int opacity = roofObsidian ? 5 : b.getLightOpacity(state, world, this.mutableGlobalPos);
                            this.overlayBuilder.build(stateId, this.biomeBuffer, opacity, overlayLight, world, this.mapProcessor, this.mutableGlobalPos, this.overlayBuilder.getOverlayBiome(), this.colorTypeCache, this.writerBiomeInfoSupplier);
                        }
                    } else if (this.hasVanillaColor(state, world, this.mutableGlobalPos)) {
                        if (h > this.topH) {
                            this.topH = h;
                        }

                        opaqueState = state;
                        break;
                    }
                } else if (!state.getMaterial().getCanBurn()
                        && !state.getMaterial().isReplaceable()
                        && state.getMaterial().getPushReaction() != EnumPushReaction.DESTROY
                        && !this.shouldOverlayCached(state)) {
                    underair = false;
                    shouldEnterGround = false;
                }
            }
        }

        if (h < lowY) {
            h = lowY;
        }

        state = opaqueState == null ? Blocks.AIR.getDefaultState() : opaqueState;
        int stateId = Block.getStateId(state);
        this.overlayBuilder.finishBuilding(pixel);
        byte light = 0;
        if (opaqueState != null) {
            light = workingLight;
            if (cave && workingLight < 15 && pixel.getNumberOfOverlays() == 0 && workingSkyLight > workingLight) {
                light = workingSkyLight;
            }
        } else {
            h = 0;
        }
        if (canReuseBiomeColours && currentPixel != null && currentPixel.getState() == stateId) {
            this.biomeBuffer[0] = currentPixel.getColourType();
            this.biomeBuffer[1] = currentPixel.getBiome();
            this.biomeBuffer[2] = currentPixel.getCustomColour();
        } else {
            this.colorTypeCache.getBlockBiomeColour(world, state, this.mutableGlobalPos, this.biomeBuffer, -1);
        }

        if (this.overlayBuilder.getOverlayBiome() != -1) {
            this.biomeBuffer[1] = this.overlayBuilder.getOverlayBiome();
        }

        boolean glowing = this.isGlowing(state);
        pixel.write(stateId, h, this.topH, this.biomeBuffer, light, glowing, cave);
    }

    /**
     * @author rfresh2
     * @reason remove limiters on map write frequency
     */
    @Overwrite
    public void onRender(BiomeColorCalculator biomeColorCalculator, OverlayManager overlayManager) {
        long before = System.nanoTime();

        try {
            if (WorldMap.crashHandler.getCrashedBy() == null) {
                synchronized(this.mapProcessor.renderThreadPauseSync) {
                    if (!this.mapProcessor.isWritingPaused()
                            && !this.mapProcessor.isWaitingForWorldUpdate()
                            && this.mapProcessor.getMapSaveLoad().isRegionDetectionComplete()
                            && this.mapProcessor.isCurrentMultiworldWritable()) {
                        if (this.mapProcessor.getWorld() == null || this.mapProcessor.isCurrentMapLocked()) {
                            return;
                        }

                        if (this.mapProcessor.getCurrentWorldId() != null
                                && !this.mapProcessor.ignoreWorld(this.mapProcessor.getWorld())
                                && (WorldMap.settings.updateChunks || WorldMap.settings.loadChunks || !this.mapProcessor.getMapWorld().isMultiplayer())) {
                            double playerX;
                            double playerY;
                            double playerZ;
                            synchronized(this.mapProcessor.mainStuffSync) {
                                if (this.mapProcessor.mainWorld != this.mapProcessor.getWorld()) {
                                    return;
                                }

                                playerX = this.mapProcessor.mainPlayerX;
                                playerY = this.mapProcessor.mainPlayerY;
                                playerZ = this.mapProcessor.mainPlayerZ;
                            }

                            XaeroWorldMapCore.ensureField();
                            int lengthX = this.endTileChunkX - this.startTileChunkX + 1;
                            int lengthZ = this.endTileChunkZ - this.startTileChunkZ + 1;
                            if (this.lastWriteTry == -1L) {
                                lengthX = 3;
                                lengthZ = 3;
                            }

                            int sizeTileChunks = lengthX * lengthZ;
                            int sizeTiles = sizeTileChunks * 4 * 4;
                            int sizeBasedTargetTime = sizeTiles * 1000 / 1500;
                            int fullUpdateTargetTime = Math.max(100, sizeBasedTargetTime);
                            long time = System.currentTimeMillis();
                            long passed = this.lastWrite == -1L ? 0L : time - this.lastWrite;
                            if (this.lastWriteTry == -1L
                                    || this.writeFreeSizeTiles != sizeTiles
                                    || this.writeFreeFullUpdateTargetTime != fullUpdateTargetTime
                                    || this.workingFrameCount > 30) {
                                this.framesFreedTime = time;
                                this.writeFreeSizeTiles = sizeTiles;
                                this.writeFreeFullUpdateTargetTime = fullUpdateTargetTime;
                                this.workingFrameCount = 0;
                            }
                            long sinceLastWrite;
                            if (this.framesFreedTime != -1L) {
                                sinceLastWrite = time - this.framesFreedTime;
                            } else {
                                sinceLastWrite = Math.min(passed, this.writeFreeSinceLastWrite);
                            }
                            sinceLastWrite = Math.max(1L, sinceLastWrite);

                            long tilesToUpdate = XaeroPlusSettingRegistry.fastMapSetting.getValue()
                                    ? (long) Math.min(sizeTiles, XaeroPlusSettingRegistry.fastMapMaxTilesPerCycle.getValue())
                                    : Math.min(sinceLastWrite * (long)sizeTiles / (long)fullUpdateTargetTime, 100L); // default

                            if (this.lastWrite == -1L || tilesToUpdate != 0L) {
                                this.lastWrite = time;
                            }

                            if (tilesToUpdate != 0L) {
                                if (this.framesFreedTime != -1L) {
                                    this.writeFreeSinceLastWrite = sinceLastWrite;
                                    this.framesFreedTime = -1L;
                                } else {
                                    int timeLimit = (int)(Math.min(sinceLastWrite, 50L) * 86960L);
                                    long writeStartNano = System.nanoTime();
                                    boolean loadChunks = WorldMap.settings.loadChunks || !this.mapProcessor.getMapWorld().isMultiplayer();
                                    boolean updateChunks = WorldMap.settings.updateChunks || !this.mapProcessor.getMapWorld().isMultiplayer();
                                    boolean ignoreHeightmaps = this.mapProcessor.getMapWorld().isIgnoreHeightmaps();
                                    boolean flowers = WorldMap.settings.flowers;
                                    boolean detailedDebug = WorldMap.settings.detailed_debug;
                                    int caveDepth = WorldMap.settings.caveModeDepth;
                                    BlockPos.MutableBlockPos mutableBlockPos3 = this.mutableBlockPos3;

                                    for(int i = 0; (long)i < tilesToUpdate; ++i) {
                                        if (this.writeMap(
                                                this.mapProcessor.getWorld(),
                                                playerX,
                                                playerY,
                                                playerZ,
                                                biomeColorCalculator,
                                                overlayManager,
                                                loadChunks,
                                                updateChunks,
                                                ignoreHeightmaps,
                                                flowers,
                                                detailedDebug,
                                                mutableBlockPos3,
                                                caveDepth
                                        )) {
                                            --i;
                                        }

                                        /** removing time limit **/
                                        if (!XaeroPlusSettingRegistry.fastMapSetting.getValue()) {
                                            if (System.nanoTime() - writeStartNano >= (long)timeLimit) {
                                                break;
                                            }
                                        }
                                    }
                                    ++this.workingFrameCount;
                                }
                            }

                            this.lastWriteTry = time;
                            int startRegionX = this.startTileChunkX >> 3;
                            int startRegionZ = this.startTileChunkZ >> 3;
                            int endRegionX = this.endTileChunkX >> 3;
                            int endRegionZ = this.endTileChunkZ >> 3;
                            boolean shouldRequestLoading = false;
                            LeveledRegion<?> nextToLoad = this.mapProcessor.getMapSaveLoad().getNextToLoadByViewing();
                            if (nextToLoad != null) {
                                synchronized(nextToLoad) {
                                    if (!nextToLoad.reloadHasBeenRequested()
                                            && !nextToLoad.hasRemovableSourceData()
                                            && (!(nextToLoad instanceof MapRegion) || !((MapRegion)nextToLoad).isRefreshing())) {
                                        shouldRequestLoading = true;
                                    }
                                }
                            } else {
                                shouldRequestLoading = true;
                            }

                            this.regionBuffer.clear();
                            int comparisonChunkX = this.playerChunkX - 16;
                            int comparisonChunkZ = this.playerChunkZ - 16;
                            LeveledRegion.setComparison(comparisonChunkX, comparisonChunkZ, 0, comparisonChunkX, comparisonChunkZ);

                            for(int visitRegionX = startRegionX; visitRegionX <= endRegionX; ++visitRegionX) {
                                for(int visitRegionZ = startRegionZ; visitRegionZ <= endRegionZ; ++visitRegionZ) {
                                    MapRegion visitRegion = this.mapProcessor.getMapRegion(this.writingLayer, visitRegionX, visitRegionZ, true);
                                    if (visitRegion != null && visitRegion.getLoadState() == 2) {
                                        visitRegion.registerVisit();
                                    }
                                    synchronized(visitRegion) {
                                        if (visitRegion.isResting()
                                                && shouldRequestLoading
                                                && !visitRegion.reloadHasBeenRequested()
                                                && !visitRegion.recacheHasBeenRequested()
                                                && (visitRegion.getLoadState() == 0 || visitRegion.getLoadState() == 4)) {
                                            visitRegion.calculateSortingChunkDistance();
                                            Misc.addToListOfSmallest(10, this.regionBuffer, visitRegion);
                                        }
                                    }
                                }
                            }

                            int toRequest = 1;
                            int counter = 0;

                            for(int i = 0; i < this.regionBuffer.size() && counter < toRequest; ++i) {
                                MapRegion region = this.regionBuffer.get(i);
                                if (region != nextToLoad || this.regionBuffer.size() <= 1) {
                                    synchronized(region) {
                                        if (!region.reloadHasBeenRequested()
                                                && !region.recacheHasBeenRequested()
                                                && (region.getLoadState() == 0 || region.getLoadState() == 4)) {
                                            region.setBeingWritten(true);
                                            this.mapProcessor.getMapSaveLoad().requestLoad(region, "writing");
                                            if (counter == 0) {
                                                this.mapProcessor.getMapSaveLoad().setNextToLoadByViewing((LeveledRegion<?>)region);
                                            }

                                            ++counter;
                                            if (region.getLoadState() == 4) {
                                                return;
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    return;
                }
            }
        } catch (Throwable var39) {
            WorldMap.crashHandler.setCrashedBy(var39);
        }
    }

    @Inject(method = "writeChunk", at = @At(value = "HEAD"), cancellable = true)
    public void writeChunk(World world, int distance, boolean onlyLoad,
                           BiomeColorCalculator biomeColorCalculator,
                           OverlayManager overlayManager,
                           boolean loadChunks, boolean updateChunks,
                           boolean ignoreHeightmaps, boolean flowers,
                           boolean detailedDebug,
                           BlockPos.MutableBlockPos mutableBlockPos3,
                           int caveDepth,
                           int caveStart,
                           int layerToWrite,
                           int tileChunkX, int tileChunkZ,
                           int tileChunkLocalX, int tileChunkLocalZ,
                           int chunkX, int chunkZ,
                           CallbackInfoReturnable<Boolean> cir) {
        if (!XaeroPlusSettingRegistry.fastMapSetting.getValue()) return;

        final Long cacheable = ChunkUtils.chunkPosToLong(chunkX, chunkZ);
        final Long cacheValue = tileUpdateCache.getIfPresent(cacheable);
        if (nonNull(cacheValue)) {
            if (cacheValue < System.currentTimeMillis() - (long) XaeroPlusSettingRegistry.fastMapWriterDelaySetting.getValue()) {
                tileUpdateCache.put(cacheable, System.currentTimeMillis());
            } else {
                cir.setReturnValue(false);
                cir.cancel();
            }
        } else {
            tileUpdateCache.put(cacheable, System.currentTimeMillis());
        }
    }

    @Redirect(method = "writeChunk", at = @At(value = "INVOKE", target = "Lxaero/map/MapWriter;loadPixel(Lnet/minecraft/world/World;Lxaero/map/region/MapBlock;Lxaero/map/region/MapBlock;Lnet/minecraft/world/chunk/Chunk;IIIIZZIZZZLnet/minecraft/util/math/BlockPos$MutableBlockPos;)V"))
    public void redirectLoadPixelForNetherFix(MapWriter instance, World world,
                                              MapBlock pixel,
                                              MapBlock currentPixel,
                                              Chunk bchunk,
                                              int insideX,
                                              int insideZ,
                                              int highY,
                                              int lowY,
                                              boolean cave,
                                              boolean fullCave,
                                              int mappedHeight,
                                              boolean canReuseBiomeColours,
                                              boolean ignoreHeightmaps,
                                              boolean flowers,
                                              BlockPos.MutableBlockPos mutableBlockPos3) {
        if (XaeroPlusSettingRegistry.netherCaveFix.getValue()) {
            final boolean nether = world.provider.getDimensionType() == DimensionType.NETHER;
            final boolean shouldForceFullInNether = !cave && nether;
            instance.loadPixel(world, pixel, currentPixel, bchunk, insideX, insideZ, highY, lowY,
                    shouldForceFullInNether || cave,
                    shouldForceFullInNether || fullCave,
                    mappedHeight, canReuseBiomeColours, ignoreHeightmaps, flowers, mutableBlockPos3);
        } else {
            instance.loadPixel(world, pixel, currentPixel, bchunk, insideX, insideZ, highY, lowY, cave, fullCave, mappedHeight, canReuseBiomeColours, ignoreHeightmaps, flowers, mutableBlockPos3);
        }
    }
}

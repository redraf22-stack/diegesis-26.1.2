# Diegesis → MC 26.1.2 (Fabric) — карта порта

Проект: `E:\GameDev\Tools\moddev\dg26` (common+fabric объединены в один Loom-проект).
Источник: ветка `1.21.1` репозитория Pocky-l/diegesis (клон в `E:\GameDev\Tools\moddev\dg`).

## Сборка
- Loom 1.17-SNAPSHOT, Gradle 9.7.1, Java 25, GRADLE_USER_HOME=`E:\gradle-home`
- MC 26.1.2, loader 0.19.5, Fabric API 0.155.2+26.1.2
- FDRF (`libs/fdrf.jar`, 26.1-3.6.30) — не нужен diegesis'у, нужен handson'у
- **Access widener**: Loom 1.17 требует namespace `official` (в 26.1 это mojmap-имена). Исходный `named`-AW не принимается → удалён.
- `RenderType.create` package-private → нужен AW:
  `accessWidener v2 official`
  `accessible method net/minecraft/client/renderer/rendertype/RenderType create (Ljava/lang/String;Lnet/minecraft/client/renderer/rendertype/RenderSetup;)Lnet/minecraft/client/renderer/rendertype/RenderType;`

## Переименования/замены API (проверено javap по minecraft-merged.jar)
| 1.21.1 | 26.1.2 |
|---|---|
| `net.minecraft.resources.ResourceLocation` | `net.minecraft.resources.Identifier` (tryParse/fromNamespaceAndPath/withDefaultNamespace) |
| `net.minecraft.client.renderer.RenderType` | `net.minecraft.client.renderer.rendertype.RenderType` |
| статические фабрики `RenderType.x()` | `net.minecraft.client.renderer.rendertype.RenderTypes.x()` |
| `RenderStateShard` + state shards | `RenderSetup.builder(RenderPipeline)` (+ `RenderPipelines.*`), `withTexture(name,id)`, `useLightmap/useOverlay/sortOnUpload`, `createRenderSetup()` |
| `net.minecraft.client.renderer.Sheets` | там же (остался), возвращает `rendertype.RenderType` |
| `net.minecraft.client.gui.GuiGraphics` | `net.minecraft.client.gui.GuiGraphicsExtractor` (методы fill/text/pose; `pose()` = `Matrix3x2fStack`) |
| `net.minecraft.util.FastColor` / `FastColor.ARGB32` | `net.minecraft.util.ARGB` (color/red/green/blue/alpha/multiply/scaleRGB) |
| `net.minecraft.client.renderer.LightTexture` | `net.minecraft.client.renderer.LevelRenderer.getLightCoords(level,pos)` (static); FULL_BRIGHT = 15728880 |
| `net.minecraft.client.resources.model.BakedModel` | удалён: `net.minecraft.client.renderer.item.ItemModel` / `ItemModelResolver` + `ItemStackRenderState`; геометрия — `client.resources.model.geometry.QuadCollection` / `BakedQuad` |
| `client.renderer.block.model.BakedQuad` | `client.resources.model.geometry.BakedQuad` |
| `client.renderer.block.model.ItemOverrides/ItemTransforms` | `client.resources.model.cuboid.ItemTransforms`; override-модели — `ItemModel` |
| `CompoundTag.getX(name)` | `getXOr(name, default)`, `getCompoundOrEmpty`, `getListOrEmpty`, `getStringOr` (Optional-варианты без Or) |
| `CompoundTag.contains(name, type)` | `contains(name)` (тип не проверяется) |
| `ItemStack.save(registries, tag)` | `ItemStack.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), stack)` |
| `ItemStack.parseOptional(registries, tag)` | `ItemStack.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), tag).result().orElse(EMPTY)` |
| `BlockEntityRenderer.render(...)` | интерфейс `BlockEntityRenderer<T,S>`: `createRenderState()`/`extractRenderState()`/`submit(..., SubmitNodeCollector, CameraRenderState)` |
| `MultiBufferSource` | **жив** (прямой путь рисования остался) |
| `VertexConsumer`, `PoseStack`, `BufferBuilder` | живы |

## Состояние компиляции
- **0 ошибок. `gradle build` успешен.** Jar: `build/libs/diegesis-1.0.0-beta.9+26.1.2.jar` (~828 КБ).
- Дальше — рантайм: запуск клиента, отлов ошибок загрузки (mixins, реестры, модели, рендер).

## Что было сломано/переделано помимо переименований
- HUD: `HudRenderCallback` → `HudElementRegistry.addFirst/addLast` + `HudElement.extractRenderState(GuiGraphicsExtractor, DeltaTracker)`.
- Команды: `ClientCommandManager` → `ClientCommands`.
- Модели: `BakedModel` удалён → `QuadCollection` через `ExtraModelKey<QuadCollection>` + `UnbakedExtraModel`
  (`SimpleModelWrapper.bake(baker, id, new ModelState(){})`), ключи в `FabricClientPlatform.key(id)`;
  `ClientPlatform.getModel` теперь возвращает `QuadCollection`.
- Скрытие блоков: `modifyModelAfterBake`/`ModelIdentifier` → `modifyBlockModelAfterBake` + `modify.state().getBlock()`.
- Мир: `WorldRenderEvents` нет → `LevelRendererMixin` @Inject TAIL на `renderLevel`, зовёт `SceneRenderer.render(...)`.
  `ClearView` (растворение сущностей у камеры) **пока не перенесён** — старые хуки `renderEntity` удалены.
- Частицы: `Particle.render` → `extract(QuadParticleRenderState, Camera, float)`; `alpha` — @Shadow.
- Ресурсы: `SimplePreparableReloadListener.reload(SharedState, Executor, PreparationBarrier, Executor)`.
- Прочее: `Direction.getNearest`→`getApproximateNearest`; `startRiding(e,bool)`→`startRiding(e,bool,bool)`;
  `player.server`→`player.level().getServer()`; `ChunkPos.x/z` — аксессоры `x()/z()`;
  `PayloadTypeRegistry.playC2S/S2C`→`serverboundPlay/clientboundPlay`;
  `S2CPlayChannelEvents`→`ClientboundPlayChannelEvents`;
  `BlockStateParser.parseForBlock(HolderLookup,...)` — lookup собирается через `HolderLookup.Provider.create(...)`;
  `Minecraft.getTextureAtlas`→`getAtlasManager().getAtlasOrThrow(...).getSprite(...)`;
  `LevelRenderer.renderLineBox` удалён — рисуем 12 рёбер сами.


## Контракты между файлами (важно!)
- `ClientPlatform.getModel(Identifier)` должен возвращать **`net.minecraft.client.resources.model.geometry.QuadCollection`**
  (`QuadCollection.EMPTY` = модель не найдена). На это уже рассчитаны SceneRenderer и DefaultColliders.
- `BlockLit.java` больше никем не вызывается (SceneRenderer перешёл на `ModelBlockRenderer.tesselateBlock`), поле `TO_WORLD`
  в SceneRenderer только заполняется — можно удалить вместе с BlockLit.
- `Mesh.quads()` возвращает `List<geometry.BakedQuad>` (MaterialInfo: translucentItemSheet).
- `SceneParticles.TYPE` ещё на `RenderType.entityNoOutline(...)` — такого метода нет, надо заменить (тип остаётся RenderType).

## 26.1 API: модели/рендер (добавлено по ходу)
- Модель блока: `ModelManager.getBlockStateModelSet().get(state)` → `BlockStateModel.collectParts()` → `BlockStateModelPart.getQuads(Direction)`.
- Квад: record `geometry.BakedQuad` (4×Vector3fc, 4×long packedUV, Direction, MaterialInfo); UV пакуются `client.model.geom.builders.UVPair.pack(u,v)`.
- Рисование: `VertexConsumer.putBakedQuad(PoseStack.Pose, BakedQuad, QuadInstance)`; свет/тинт/оверлей — в `QuadInstance`.
- Предметы: `ItemModelResolver.updateForTopItem(ItemStackRenderState, stack, ItemDisplayContext, Level, ItemOwner, seed)` → `state.submit(...)`.
- Fabric: `ModelLoadingPlugin.Context.addModel(ExtraModelKey<T>, UnbakedExtraModel<T>)`, `modifyBlockModelAfterBake`; `HudElementRegistry` (пакет `...rendering.v1.hud`) + `HudElement.extractRenderState(GuiGraphicsExtractor, DeltaTracker)`; `ClientCommands.literal(...)`.



## Порядок работы
1. DiegesisRenderTypes — фундамент (кастомные типы через RenderSetup + AW). ✅ сделан на готовых RenderTypes-фабриках.
2. BlockLit / SmoothPass / SceneRenderer (рисование сцены).
3. ItemShapes / PlacedShape / HideableFabricModel (модели предметов → ItemModelResolver).
4. GUI: FocusScreen, GuiGraphicsMixin, HudSlide. ✅ сделано (агент).
5. SableSubLevels (compile-only зависимость sable-companion — вероятно, вырезать/заглушить). ✅ удалён.
6. Затем handson_delight (`E:\GameDev\Tools\moddev\hd`, ветка 1.21.1) тем же способом.

## ВАЖНО: хуки мира в 26.1
- В Fabric API 26.1 **нет `WorldRenderEvents`** (модуль отсутствует). Рисовать в мире можно только через
  миксин в `LevelRenderer.renderLevel(...)` и `SubmitNodeCollector`, либо через BlockEntityRenderer.
- `LevelRenderer.renderLevel` новая сигнатура:
  `(GraphicsResourceAllocator, DeltaTracker, boolean, CameraRenderState, Matrix4fc, GpuBufferSlice, Vector4f, boolean, ChunkSectionsToRender)`
- `LevelRenderer.renderEntity(...)` **удалён** (рендер сущностей переехал в render states) → `LevelRendererMixin`
  (`ClearView`: растворение сущностей у камеры) надо переписывать на новую систему.
- `Camera`: `position()` вместо `getPosition()`, `rotation()`, `blockPosition()`.
- Fabric API 26.1 переименования: `HudRenderCallback` → `net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry`
  (+ `HudElement`); `ClientCommandManager` → `ClientCommands`; `PayloadTypeRegistry` — сигнатуры изменились;
  `ModelLoadingPlugin.Context`: `addModels` → `addModel(ExtraModelKey, UnbakedExtraModel)`,
  `modifyModelAfterBake` → `modifyBlockModelAfterBake`.
- Модели блоков/предметов в 26.1: `BlockStateModelSet`, `client.renderer.block.dispatch.BlockStateModel`,
  `client.resources.model.geometry.QuadCollection`; `ModelIdentifier`/`BlockModelShaper` удалены.


## Проверка аддонов без рук (15.11.2026, ночь)
- `AutoTest` умеет две вещи для dev-прогонов: `-Ddiegesis.autotest.place=<ns:id>` ставит блок рядом с игроком
  (только одиночная игра, через свой `ServerLevel`; ждёт `player.tickCount >= 40`, иначе ставит в 0,0,0) и
  `-Ddiegesis.autotest.click=<узел>` кликает узел сцены (`SceneEvent.Click`) — так открывается книга рецептов.
- Поставленный блок пробуется первым независимо от расстояния (`placeTarget` в `find`), иначе автотест открывает
  ближайшую чужую станцию.

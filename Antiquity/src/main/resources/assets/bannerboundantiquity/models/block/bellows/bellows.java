// Made with Blockbench 5.1.4
// Exported for Minecraft version 1.17 or later with Mojang mappings
// Paste this class into your mod and generate all required imports


public class bellows<T extends Entity> extends EntityModel<T> {
	// This layer location should be baked with EntityRendererProvider.Context in the entity renderer and passed into this model's constructor
	public static final ModelLayerLocation LAYER_LOCATION = new ModelLayerLocation(new ResourceLocation("modid", "bellows"), "main");
	private final ModelPart Bellows_Top;
	private final ModelPart Spine;
	private final ModelPart Bellows Base;

	public bellows(ModelPart root) {
		this.Bellows_Top = root.getChild("Bellows_Top");
		this.Spine = root.getChild("Spine");
		this.Bellows Base = root.getChild("Bellows Base");
	}

	public static LayerDefinition createBodyLayer() {
		MeshDefinition meshdefinition = new MeshDefinition();
		PartDefinition partdefinition = meshdefinition.getRoot();

		PartDefinition Bellows_Top = partdefinition.addOrReplaceChild("Bellows_Top", CubeListBuilder.create().texOffs(0, 14).addBox(-3.0F, -2.0F, -1.0F, 4.0F, 2.0F, 12.0F, new CubeDeformation(0.0F))
		.texOffs(28, 40).addBox(-2.0F, -2.0F, -2.0F, 2.0F, 2.0F, 1.0F, new CubeDeformation(0.0F))
		.texOffs(36, 33).addBox(-7.0F, -2.0F, 0.0F, 4.0F, 2.0F, 10.0F, new CubeDeformation(0.0F))
		.texOffs(0, 37).addBox(1.0F, -2.0F, 0.0F, 4.0F, 2.0F, 10.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(1.0F, 21.0F, -5.0F, 0.4363F, 0.0F, 0.0F));

		PartDefinition Spine = partdefinition.addOrReplaceChild("Spine", CubeListBuilder.create().texOffs(0, 28).addBox(-9.0F, -1.0F, 0.0F, 10.0F, 1.0F, 8.0F, new CubeDeformation(0.0F))
		.texOffs(32, 0).addBox(-9.0F, -3.0F, 0.0F, 10.0F, 1.0F, 8.0F, new CubeDeformation(0.0F))
		.texOffs(28, 45).addBox(-8.0F, -2.0F, 0.0F, 8.0F, 1.0F, 7.0F, new CubeDeformation(0.0F))
		.texOffs(30, 53).addBox(-8.0F, -4.0F, 3.0F, 8.0F, 1.0F, 4.0F, new CubeDeformation(0.0F))
		.texOffs(0, 53).addBox(-9.0F, -5.0F, 3.0F, 10.0F, 1.0F, 5.0F, new CubeDeformation(0.0F)), PartPose.offset(4.0F, 22.0F, -4.0F));

		PartDefinition Bellows Base = partdefinition.addOrReplaceChild("Bellows Base", CubeListBuilder.create().texOffs(0, 0).addBox(-3.0F, -2.0F, -1.0F, 4.0F, 2.0F, 12.0F, new CubeDeformation(0.0F))
		.texOffs(28, 37).addBox(-2.0F, -2.0F, -2.0F, 2.0F, 2.0F, 1.0F, new CubeDeformation(0.0F))
		.texOffs(32, 9).addBox(-7.0F, -2.0F, 0.0F, 4.0F, 2.0F, 10.0F, new CubeDeformation(0.0F))
		.texOffs(36, 21).addBox(1.0F, -2.0F, 0.0F, 4.0F, 2.0F, 10.0F, new CubeDeformation(0.0F)), PartPose.offset(1.0F, 24.0F, -5.0F));

		return LayerDefinition.create(meshdefinition, 128, 128);
	}

	@Override
	public void setupAnim(Entity entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {

	}

	@Override
	public void renderToBuffer(PoseStack poseStack, VertexConsumer vertexConsumer, int packedLight, int packedOverlay, float red, float green, float blue, float alpha) {
		Bellows_Top.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
		Spine.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
		Bellows Base.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
	}
}
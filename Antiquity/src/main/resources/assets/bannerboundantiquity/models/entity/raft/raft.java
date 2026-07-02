// Made with Blockbench 5.1.4
// Exported for Minecraft version 1.17 or later with Mojang mappings
// Paste this class into your mod and generate all required imports


public class raft<T extends Entity> extends EntityModel<T> {
	// This layer location should be baked with EntityRendererProvider.Context in the entity renderer and passed into this model's constructor
	public static final ModelLayerLocation LAYER_LOCATION = new ModelLayerLocation(new ResourceLocation("modid", "raft"), "main");
	private final ModelPart bottom;
	private final ModelPart left_paddle;
	private final ModelPart right_paddle;

	public raft(ModelPart root) {
		this.bottom = root.getChild("bottom");
		this.left_paddle = root.getChild("left_paddle");
		this.right_paddle = root.getChild("right_paddle");
	}

	public static LayerDefinition createBodyLayer() {
		MeshDefinition meshdefinition = new MeshDefinition();
		PartDefinition partdefinition = meshdefinition.getRoot();

		PartDefinition bottom = partdefinition.addOrReplaceChild("bottom", CubeListBuilder.create().texOffs(0, 0).addBox(-3.0F, -2.0F, -13.0F, 6.0F, 2.0F, 48.0F, new CubeDeformation(0.0F))
		.texOffs(108, 102).addBox(-11.0F, -7.1F, -13.0F, 2.0F, 5.0F, 48.0F, new CubeDeformation(0.0F))
		.texOffs(0, 150).addBox(9.0F, -7.1F, -13.0F, 2.0F, 5.0F, 48.0F, new CubeDeformation(0.0F))
		.texOffs(172, 166).addBox(-9.0F, -10.1F, -13.0F, 18.0F, 3.0F, 3.0F, new CubeDeformation(0.0F))
		.texOffs(154, 155).addBox(-9.0F, -10.2F, -10.0F, 18.0F, 9.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offset(-0.1F, 23.9F, -11.0F));

		PartDefinition cube_r1 = bottom.addOrReplaceChild("cube_r1", CubeListBuilder.create().texOffs(188, 202).addBox(-3.0F, -6.0F, -1.0F, 4.0F, 6.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(1.1F, -5.9F, 57.0F, -0.2182F, 0.0F, 0.0F));

		PartDefinition cube_r2 = bottom.addOrReplaceChild("cube_r2", CubeListBuilder.create().texOffs(108, 51).addBox(-3.0F, -3.0F, -1.0F, 4.0F, 3.0F, 48.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-10.0F, -7.1F, -12.0F, 0.0F, 0.0F, -0.0873F));

		PartDefinition cube_r3 = bottom.addOrReplaceChild("cube_r3", CubeListBuilder.create().texOffs(108, 0).addBox(-3.0F, -3.0F, -1.0F, 4.0F, 3.0F, 48.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(12.0F, -7.1F, -12.0F, 0.0F, 0.0F, 0.0873F));

		PartDefinition cube_r4 = bottom.addOrReplaceChild("cube_r4", CubeListBuilder.create().texOffs(136, 180).addBox(-3.0F, -3.0F, -1.0F, 4.0F, 3.0F, 12.0F, new CubeDeformation(0.0F))
		.texOffs(160, 202).addBox(-1.0F, 0.1F, -1.0F, 2.0F, 5.0F, 12.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-6.9F, -7.1F, 47.1F, 0.0F, 0.6545F, 0.0F));

		PartDefinition cube_r5 = bottom.addOrReplaceChild("cube_r5", CubeListBuilder.create().texOffs(168, 187).addBox(-3.0F, -3.0F, -1.0F, 4.0F, 3.0F, 12.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(8.6F, -7.0F, 48.1F, 0.0F, -0.6545F, 0.0F));

		PartDefinition cube_r6 = bottom.addOrReplaceChild("cube_r6", CubeListBuilder.create().texOffs(200, 187).addBox(-1.0F, -5.0F, -1.0F, 2.0F, 5.0F, 12.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(6.6F, -2.0F, 47.1F, 0.0F, -0.6545F, 0.0F));

		PartDefinition cube_r7 = bottom.addOrReplaceChild("cube_r7", CubeListBuilder.create().texOffs(172, 172).addBox(-3.0F, -3.0F, -1.0F, 4.0F, 3.0F, 12.0F, new CubeDeformation(0.0F))
		.texOffs(100, 201).addBox(-1.0F, 0.0F, -1.0F, 2.0F, 5.0F, 12.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-10.0F, -7.2F, 36.0F, 0.0F, 0.2618F, 0.0F));

		PartDefinition cube_r8 = bottom.addOrReplaceChild("cube_r8", CubeListBuilder.create().texOffs(100, 186).addBox(-3.0F, -3.0F, -1.0F, 4.0F, 3.0F, 12.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(12.0F, -7.1F, 36.2F, 0.0F, -0.2618F, 0.0F));

		PartDefinition cube_r9 = bottom.addOrReplaceChild("cube_r9", CubeListBuilder.create().texOffs(132, 195).addBox(-1.0F, -5.0F, -1.0F, 2.0F, 5.0F, 12.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(10.0F, -2.1F, 36.0F, 0.0F, -0.2618F, 0.0F));

		PartDefinition cube_r10 = bottom.addOrReplaceChild("cube_r10", CubeListBuilder.create().texOffs(136, 158).addBox(-3.0F, -1.0F, -1.0F, 4.0F, 1.0F, 5.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(1.1F, -1.8F, 45.3F, 0.1309F, 0.0436F, 0.0F));

		PartDefinition cube_r11 = bottom.addOrReplaceChild("cube_r11", CubeListBuilder.create().texOffs(0, 203).addBox(-3.0F, -1.0F, -1.0F, 4.0F, 1.0F, 10.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-3.3F, -1.8F, 44.6F, 0.1309F, 0.3927F, 0.0F));

		PartDefinition cube_r12 = bottom.addOrReplaceChild("cube_r12", CubeListBuilder.create().texOffs(194, 155).addBox(-3.0F, -1.0F, -1.0F, 4.0F, 1.0F, 10.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(5.0F, -2.1F, 46.6F, 0.1309F, -0.6109F, 0.0F));

		PartDefinition cube_r13 = bottom.addOrReplaceChild("cube_r13", CubeListBuilder.create().texOffs(136, 166).addBox(-5.0F, -2.0F, -1.0F, 6.0F, 2.0F, 12.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(2.0F, 0.0F, 35.7F, 0.1309F, 0.0F, 0.0F));

		PartDefinition cube_r14 = bottom.addOrReplaceChild("cube_r14", CubeListBuilder.create().texOffs(100, 158).addBox(-5.0F, -2.0F, -1.0F, 6.0F, 2.0F, 12.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(8.2F, -0.6F, 35.7F, 0.1309F, -0.2182F, -0.1745F));

		PartDefinition cube_r15 = bottom.addOrReplaceChild("cube_r15", CubeListBuilder.create().texOffs(100, 172).addBox(-5.0F, -2.0F, -1.0F, 6.0F, 2.0F, 12.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-4.3F, 0.0F, 35.0F, 0.1309F, 0.2182F, 0.1745F));

		PartDefinition cube_r16 = bottom.addOrReplaceChild("cube_r16", CubeListBuilder.create().texOffs(0, 100).addBox(-5.0F, -2.0F, -1.0F, 6.0F, 2.0F, 48.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-4.3F, -0.1F, -12.0F, 0.0F, 0.0F, 0.1745F));

		PartDefinition cube_r17 = bottom.addOrReplaceChild("cube_r17", CubeListBuilder.create().texOffs(0, 50).addBox(-5.0F, -2.0F, -1.0F, 6.0F, 2.0F, 48.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(8.3F, -0.9F, -12.0F, 0.0F, 0.0F, -0.1745F));

		PartDefinition left_paddle = partdefinition.addOrReplaceChild("left_paddle", CubeListBuilder.create(), PartPose.offset(-10.1F, 13.7F, -5.0F));

		PartDefinition right_paddle = partdefinition.addOrReplaceChild("right_paddle", CubeListBuilder.create(), PartPose.offsetAndRotation(6.9F, 4.7F, 1.0F, 0.0F, 0.0F, 0.2618F));

		PartDefinition cube_r18 = right_paddle.addOrReplaceChild("cube_r18", CubeListBuilder.create().texOffs(28, 203).addBox(-3.0F, -1.7F, -1.0F, 4.0F, 1.0F, 4.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(22.5F, 20.4F, -1.0F, 0.0F, 0.0F, 0.6981F));

		PartDefinition cube_r19 = right_paddle.addOrReplaceChild("cube_r19", CubeListBuilder.create().texOffs(100, 155).addBox(-23.9F, -0.7F, -1.0F, 25.0F, 1.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(20.0F, 17.0F, 0.0F, 0.0F, 0.0F, 0.6981F));

		return LayerDefinition.create(meshdefinition, 256, 256);
	}

	@Override
	public void setupAnim(Entity entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {

	}

	@Override
	public void renderToBuffer(PoseStack poseStack, VertexConsumer vertexConsumer, int packedLight, int packedOverlay, float red, float green, float blue, float alpha) {
		bottom.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
		left_paddle.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
		right_paddle.render(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
	}
}
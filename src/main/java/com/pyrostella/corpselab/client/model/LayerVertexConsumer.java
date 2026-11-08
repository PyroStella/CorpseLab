package com.pyrostella.corpselab.client.model;

import com.mojang.blaze3d.vertex.VertexConsumer;

/** 通用层只接受成功绑定的 ModelPart 输出，避免独立物品等几何停留在尸体锚点 */
public final class LayerVertexConsumer implements VertexConsumer {
    private final VertexConsumer delegate;

    public LayerVertexConsumer(VertexConsumer delegate) {
        this.delegate = delegate;
    }

    @Override
    public VertexConsumer vertex(double x, double y, double z) {
        if (LayerPartContext.acceptsVertices()) this.delegate.vertex(x, y, z);
        return this;
    }

    @Override
    public VertexConsumer color(int red, int green, int blue, int alpha) {
        if (LayerPartContext.acceptsVertices()) this.delegate.color(red, green, blue, alpha);
        return this;
    }

    @Override
    public VertexConsumer uv(float u, float v) {
        if (LayerPartContext.acceptsVertices()) this.delegate.uv(u, v);
        return this;
    }

    @Override
    public VertexConsumer overlayCoords(int u, int v) {
        if (LayerPartContext.acceptsVertices()) this.delegate.overlayCoords(u, v);
        return this;
    }

    @Override
    public VertexConsumer uv2(int u, int v) {
        if (LayerPartContext.acceptsVertices()) this.delegate.uv2(u, v);
        return this;
    }

    @Override
    public VertexConsumer normal(float x, float y, float z) {
        if (LayerPartContext.acceptsVertices()) this.delegate.normal(x, y, z);
        return this;
    }

    @Override
    public void endVertex() {
        if (LayerPartContext.acceptsVertices()) this.delegate.endVertex();
    }

    @Override
    public void defaultColor(int red, int green, int blue, int alpha) {
        if (LayerPartContext.acceptsVertices()) this.delegate.defaultColor(red, green, blue, alpha);
    }

    @Override
    public void unsetDefaultColor() {
        if (LayerPartContext.acceptsVertices()) this.delegate.unsetDefaultColor();
    }
}

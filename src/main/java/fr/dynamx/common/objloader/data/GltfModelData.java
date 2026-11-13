package fr.dynamx.common.objloader.data;

import com.jme3.math.Vector3f;
import com.modularmods.mcgltf.dynamx.MCglTF;
import de.javagl.jgltf.dynamx.model.*;
import fr.dynamx.api.dxmodel.DxModelPath;
import lombok.Getter;

import javax.annotation.Nullable;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class GltfModelData extends DxModelData {

    @Getter
    private final GltfModel gltfModel;

    public GltfModelData(DxModelPath objModelPath) {
        super(objModelPath);
        gltfModel = MCglTF.readModel(objModelPath);
    }

    @Override
    public float[] getVerticesPos(String objectName) {
        String objectNameLower = objectName.toLowerCase();
        List<float[]> positionList = new ArrayList<>();
        int size = 0;

        for (NodeModel nodeModel : getNodeModels()) {
            if (!nodeModel.getName().equalsIgnoreCase(objectNameLower)) {
                continue;
            }
            for (MeshModel meshModel : nodeModel.getMeshModels()) {
                for (MeshPrimitiveModel meshPrimitiveModel : meshModel.getMeshPrimitiveModels()) {
                    AccessorModel accessorModel = meshPrimitiveModel.getAttributes().get("POSITION");
                    if (accessorModel.getComponentType() != 5126) {
                        return new float[0];
                    }
                    FloatBuffer floatBuffer = accessorModel.getBufferViewModel().getBufferViewData().asFloatBuffer();
                    float[] pos = new float[floatBuffer.limit()];
                    size += floatBuffer.limit();
                    floatBuffer.get(pos);
                    positionList.add(pos);
                }
            }
            break;
        }
        float[] pos = new float[size];
        int currentPosition = 0;
        for (float[] posi : positionList) {
            System.arraycopy(posi, 0, pos, currentPosition, posi.length);
            currentPosition += posi.length;
        }
        return pos;
    }

    @Override
    public int[] getMeshIndices(String objectName) {
        String objectNameLower = objectName.toLowerCase();
        List<int[]> indicesList = new ArrayList<>();
        int size = 0;

        for (NodeModel nodeModel : getNodeModels()) {
            if (!nodeModel.getName().equalsIgnoreCase(objectNameLower)) {
                continue;
            }
            for (MeshModel meshModel : nodeModel.getMeshModels()) {
                for (MeshPrimitiveModel meshPrimitiveModel : meshModel.getMeshPrimitiveModels()) {
                    AccessorModel accessorModel = meshPrimitiveModel.getIndices();
                    if (accessorModel.getComponentType() != 5123) {
                        return new int[0];
                    }
                    ShortBuffer shortBuffer = accessorModel.getBufferViewModel().getBufferViewData().asShortBuffer();
                    int[] indices = new int[shortBuffer.limit()];
                    size += shortBuffer.limit();
                    for (int i = 0; i < shortBuffer.limit(); i++) {
                        indices[i] = shortBuffer.get();
                    }
                    indicesList.add(indices);
                }
            }
            break;
        }
        int[] indices = new int[size];
        int currentPosition = 0;
        for (int[] ints : indicesList) {
            System.arraycopy(ints, 0, indices, currentPosition, ints.length);
            currentPosition += ints.length;
        }
        return indices;
    }

    /**
     * {@inheritDoc} <br>
     * Lues dans l'attribut TEXCOORD_0 des primitives du noeud, dans le même ordre que POSITION l'est par
     * {@link GltfModelData#getVerticesPos(String)}, et prises telles quelles (un gltf les écrit déjà dans
     * l'espace d'échantillonnage). Rien n'est renvoyé si une primitive ne les porte pas en floats : elle
     * décalerait toutes les coordonnées suivantes d'un sommet.
     */
    @Override
    public float[] getVerticesTexCoords(String objectName) {
        String objectNameLower = objectName.toLowerCase();
        List<float[]> texCoordList = new ArrayList<>();
        int size = 0;

        for (NodeModel nodeModel : getNodeModels()) {
            if (!nodeModel.getName().equalsIgnoreCase(objectNameLower)) {
                continue;
            }
            for (MeshModel meshModel : nodeModel.getMeshModels()) {
                for (MeshPrimitiveModel meshPrimitiveModel : meshModel.getMeshPrimitiveModels()) {
                    AccessorModel accessorModel = meshPrimitiveModel.getAttributes().get("TEXCOORD_0");
                    if (accessorModel == null || accessorModel.getComponentType() != 5126) {
                        return new float[0];
                    }
                    FloatBuffer floatBuffer = accessorModel.getBufferViewModel().getBufferViewData().asFloatBuffer();
                    float[] texCoords = new float[floatBuffer.limit()];
                    size += floatBuffer.limit();
                    floatBuffer.get(texCoords);
                    texCoordList.add(texCoords);
                }
            }
            break;
        }
        float[] texCoords = new float[size];
        int currentPosition = 0;
        for (float[] read : texCoordList) {
            System.arraycopy(read, 0, texCoords, currentPosition, read.length);
            currentPosition += read.length;
        }
        return texCoords;
    }

    @Override
    public Vector3f getMeshMin(String name, @Nullable Vector3f result) {
        float[] verticesPos = getVerticesPos(name);
        if (verticesPos.length == 0) {
            if (result == null)
                return new Vector3f();
            return result.set(0, 0, 0);
        }
        float minX = verticesPos[0];
        float minY = verticesPos[1];
        float minZ = verticesPos[2];
        for (int i = 1; i < verticesPos.length / 3; i++) {
            if (verticesPos[i * 3] < minX) minX = verticesPos[i * 3];
            if (verticesPos[i * 3 + 1] < minY) minY = verticesPos[i * 3 + 1];
            if (verticesPos[i * 3 + 2] < minZ) minZ = verticesPos[i * 3 + 2];
        }
        if (result == null)
            return new Vector3f(minX, minY, minZ);
        return result.set(minX, minY, minZ);
    }

    @Override
    public Vector3f getMeshMax(String name, @Nullable Vector3f result) {
        float[] verticesPos = getVerticesPos(name);
        if (verticesPos.length == 0) {
            if (result == null)
                return new Vector3f();
            return result.set(0, 0, 0);
        }
        float maxX = verticesPos[0];
        float maxY = verticesPos[1];
        float maxZ = verticesPos[2];
        for (int i = 1; i < verticesPos.length / 3; i++) {
            if (verticesPos[i * 3] > maxX) maxX = verticesPos[i * 3];
            if (verticesPos[i * 3 + 1] > maxY) maxY = verticesPos[i * 3 + 1];
            if (verticesPos[i * 3 + 2] > maxZ) maxZ = verticesPos[i * 3 + 2];
        }
        if (result == null)
            return new Vector3f(maxX, maxY, maxZ);
        return result.set(maxX, maxY, maxZ);
    }

    @Override
    public Vector3f getMinOfModel(@Nullable Vector3f result) {
        Vector3f firstMin = getMeshMin(getNodeModels().get(0).getName().toLowerCase(), result);
        float minX = firstMin.x;
        float minY = firstMin.y;
        float minZ = firstMin.z;
        List<NodeModel> nodeModels = getNodeModels();
        for (int i = 1; i < nodeModels.size(); i++) {
            Vector3f min = getMeshMin(nodeModels.get(i).getName().toLowerCase(), result);
            if (min.x < minX) minX = min.x;
            if (min.y < minY) minY = min.y;
            if (min.z < minZ) minZ = min.z;
        }
        if (result == null)
            return new Vector3f(minX, minY, minZ);
        return result.set(minX, minY, minZ);
    }

    @Override
    public Vector3f getMaxOfModel(@Nullable Vector3f result) {
        Vector3f firstMax = getMeshMax(getNodeModels().get(0).getName().toLowerCase(), result);
        float maxX = firstMax.x;
        float maxY = firstMax.y;
        float maxZ = firstMax.z;
        List<NodeModel> nodeModels = getNodeModels();
        for (int i = 1; i < nodeModels.size(); i++) {
            Vector3f max = getMeshMax(nodeModels.get(i).getName().toLowerCase(), result);
            if (max.x > maxX) maxX = max.x;
            if (max.y > maxY) maxY = max.y;
            if (max.z > maxZ) maxZ = max.z;
        }
        if (result == null)
            return new Vector3f(maxX, maxY, maxZ);
        return result.set(maxX, maxY, maxZ);
    }

    public List<NodeModel> getNodeModels() {
        return gltfModel == null ? new ArrayList<>() : gltfModel.getNodeModels();
    }

    public NodeModel getNodeModel(String objectName) {
        for (NodeModel nodeModel : getNodeModels()) {
            if (nodeModel.getName().toLowerCase().contains(objectName.toLowerCase())) {
                return nodeModel;
            }
        }
        return null;
    }

    @Override
    public List<String> getMeshNames() {
        return getNodeModels().stream().map(n -> n.getName().toLowerCase()).collect(Collectors.toList());
    }

    /**
     * {@inheritDoc} <br>
     * Les sommets d'un gltf sont stockés dans l'espace de leur noeud : c'est la matrice sous laquelle le
     * renderer les dessine, les transformations locales du noeud et de tous ses parents combinées.
     *
     * @see com.modularmods.mcgltf.dynamx.RenderedGltfModel#findGlobalTransform(NodeModel)
     */
    @Nullable
    @Override
    public float[] getMeshTransform(String objectName) {
        // Apparié comme les sommets eux-mêmes (voir getVerticesPos) : la transformation doit être celle du noeud
        // dont ils ont été lus
        for (NodeModel nodeModel : getNodeModels()) {
            if (nodeModel.getName().equalsIgnoreCase(objectName)) {
                return nodeModel.computeGlobalTransform(null);
            }
        }
        return null;
    }
}

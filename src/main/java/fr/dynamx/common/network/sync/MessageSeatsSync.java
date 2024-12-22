package fr.dynamx.common.network.sync;

import fr.dynamx.api.entities.IModuleContainer;
import fr.dynamx.common.DynamXContext;
import fr.dynamx.common.contentpack.parts.BasePartSeat;
import fr.dynamx.common.entities.PhysicsEntity;
import fr.dynamx.common.network.packets.PhysicsEntityMessage;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.HashMap;
import java.util.Map;

import static fr.dynamx.common.DynamXMain.log;

public class MessageSeatsSync extends PhysicsEntityMessage<MessageSeatsSync> {
    private final Map<Byte, Integer> seatToEntity = new HashMap<>();

    public MessageSeatsSync() {
        super(null);
    }

    public MessageSeatsSync(IModuleContainer.ISeatsContainer vehicleEntity) {
        super(vehicleEntity.cast());
        for (Map.Entry<BasePartSeat, Entity> e : vehicleEntity.getSeats().getSeatToPassengerMap().entrySet()) {
            seatToEntity.put(e.getKey().getId(), e.getValue().getEntityId());
        }
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        super.fromBytes(buf);
        int size = buf.readInt();
        for (int i = 0; i < size; i++)
            seatToEntity.put(buf.readByte(), buf.readInt());
    }

    @Override
    @SideOnly(Side.CLIENT)
    protected void processMessageClient(PhysicsEntityMessage<?> message, PhysicsEntity<?> entity, EntityPlayer player) {
        if (!(entity instanceof IModuleContainer.ISeatsContainer) || !((IModuleContainer.ISeatsContainer) entity).hasSeats()) {
            log.fatal("Received seats packet for an entity that have no seats !");
            return;
        }
        DynamXContext.getPhysicsWorld(entity.world).schedule(() -> ((IModuleContainer.ISeatsContainer) entity).getSeats().updateSeats((MessageSeatsSync) message, entity.getSynchronizer()));
    }

    @Override
    protected void processMessageServer(PhysicsEntityMessage<?> message, PhysicsEntity<?> entity, EntityPlayer player) {
        throw new IllegalStateException();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        super.toBytes(buf);
        buf.writeInt(seatToEntity.size());
        for (Map.Entry<Byte, Integer> e : seatToEntity.entrySet()) {
            buf.writeByte(e.getKey());
            buf.writeInt(e.getValue());
        }
    }

    public Map<Byte, Integer> getSeatToEntity() {
        return seatToEntity;
    }
}

-- El servicio marca los holds como SOLD cuando el asiento se compra (SeatHoldServiceImpl.releaseHold),
-- pero la restricción original solo permitía HOLD y EXPIRED, lo que hacía fallar toda compra con hold previo.
ALTER TABLE seat_holds DROP CONSTRAINT IF EXISTS seat_holds_status_check;

ALTER TABLE seat_holds
    ADD CONSTRAINT seat_holds_status_check CHECK (status IN ('HOLD', 'EXPIRED', 'SOLD'));

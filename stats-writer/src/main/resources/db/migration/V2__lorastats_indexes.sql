CREATE INDEX idx_lorastats_tsdevice_uid
    ON lorastats (ts_device DESC, uid DESC);

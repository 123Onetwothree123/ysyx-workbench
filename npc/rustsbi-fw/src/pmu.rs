//! RustSBI NPC PMU 的 DTB 事件映射。
//!
//! 只解析 `riscv,pmu` 节点中的标准 OpenSBI 属性：
//! `riscv,event-to-mhpmevent`、`riscv,event-to-mhpmcounters`。
//! 固件是单 hart 的，初始化后只读，因此使用静态表避免堆分配。

const MAX_EVENTS: usize = 32;
const MAX_COUNTER_MAPS: usize = 16;

static mut EVENT_SELECT: [(u32, u64); MAX_EVENTS] = [(0, 0); MAX_EVENTS];
static mut EVENT_SELECT_COUNT: usize = 0;
static mut EVENT_COUNTER_MAP: [(u32, u32, u32); MAX_COUNTER_MAPS] =
    [(0, 0, 0); MAX_COUNTER_MAPS];
static mut EVENT_COUNTER_MAP_COUNT: usize = 0;

fn be32(data: &[u8], offset: usize) -> Option<u32> {
    let bytes = data.get(offset..offset + 4)?;
    Some(u32::from_be_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]))
}

fn align4(value: usize) -> usize {
    (value + 3) & !3
}

fn read_cstr(data: &[u8], offset: usize) -> Option<&[u8]> {
    let tail = data.get(offset..)?;
    let end = tail.iter().position(|byte| *byte == 0)?;
    Some(&tail[..end])
}

fn property_name(strings: &[u8], nameoff: u32) -> Option<&[u8]> {
    read_cstr(strings, nameoff as usize)
}

unsafe fn clear_maps() {
    EVENT_SELECT_COUNT = 0;
    EVENT_COUNTER_MAP_COUNT = 0;
}

unsafe fn parse_event_select(data: &[u8]) {
    let rows = data.len() / 12;
    for row in 0..rows {
        if EVENT_SELECT_COUNT >= MAX_EVENTS {
            break;
        }
        let base = row * 12;
        let Some(event_idx) = be32(data, base) else { break };
        let Some(high) = be32(data, base + 4) else { break };
        let Some(low) = be32(data, base + 8) else { break };
        EVENT_SELECT[EVENT_SELECT_COUNT] =
            (event_idx, ((high as u64) << 32) | low as u64);
        EVENT_SELECT_COUNT += 1;
    }
}

unsafe fn parse_counter_map(data: &[u8]) {
    let rows = data.len() / 12;
    for row in 0..rows {
        if EVENT_COUNTER_MAP_COUNT >= MAX_COUNTER_MAPS {
            break;
        }
        let base = row * 12;
        let Some(start) = be32(data, base) else { break };
        let Some(end) = be32(data, base + 4) else { break };
        let Some(mask) = be32(data, base + 8) else { break };
        EVENT_COUNTER_MAP[EVENT_COUNTER_MAP_COUNT] = (start, end, mask);
        EVENT_COUNTER_MAP_COUNT += 1;
    }
}

/// 从内嵌 DTB 初始化事件映射；失败时保持空表。
pub fn init(dtb: &[u8]) {
    unsafe {
        clear_maps();

        if be32(dtb, 0) != Some(0xd00d_feed) {
            return;
        }
        let Some(total_size) = be32(dtb, 4).map(|value| value as usize) else {
            return;
        };
        let Some(struct_offset) = be32(dtb, 8).map(|value| value as usize) else {
            return;
        };
        let Some(strings_offset) = be32(dtb, 12).map(|value| value as usize) else {
            return;
        };
        let Some(strings_size) = be32(dtb, 32).map(|value| value as usize) else {
            return;
        };
        if total_size > dtb.len()
            || struct_offset >= total_size
            || strings_offset >= total_size
            || strings_size > total_size - strings_offset
        {
            return;
        }
        let strings = &dtb[strings_offset..strings_offset + strings_size];

        let mut offset = struct_offset;
        let mut depth = 0usize;
        let mut pmu_depth: Option<usize> = None;
        while offset + 4 <= total_size {
            let Some(token) = be32(dtb, offset) else { break };
            offset += 4;
            match token {
                1 => {
                    let Some(name) = read_cstr(dtb, offset) else { break };
                    offset = align4(offset + name.len() + 1);
                    if name == b"pmu" {
                        pmu_depth = Some(depth);
                    }
                    depth += 1;
                }
                2 => {
                    if let Some(active) = pmu_depth {
                        if depth == active + 1 {
                            pmu_depth = None;
                        }
                    }
                    depth = depth.saturating_sub(1);
                }
                3 => {
                    let Some(length) = be32(dtb, offset).map(|value| value as usize) else {
                        break;
                    };
                    let Some(nameoff) = be32(dtb, offset + 4) else { break };
                    offset += 8;
                    let Some(data) = dtb.get(offset..offset + length) else {
                        break;
                    };
                    offset = align4(offset + length);
                    if pmu_depth == Some(depth - 1) {
                        if let Some(name) = property_name(strings, nameoff) {
                            if name == b"riscv,event-to-mhpmevent" {
                                parse_event_select(data);
                            } else if name == b"riscv,event-to-mhpmcounters" {
                                parse_counter_map(data);
                            }
                        }
                    }
                }
                4 => {}
                9 => break,
                _ => break,
            }
        }
    }
}

/// 通用事件对应的 mhpmevent 编码；没有映射时返回 None。
pub fn event_select(event_idx: u32) -> Option<u64> {
    unsafe {
        for index in 0..EVENT_SELECT_COUNT {
            let (event, select) = EVENT_SELECT[index];
            if event == event_idx {
                return Some(select);
            }
        }
    }
    None
}

/// 通用事件允许的硬件计数器位图；没有映射时返回 None。
pub fn event_counter_mask(event_idx: u32) -> Option<u32> {
    unsafe {
        for index in 0..EVENT_COUNTER_MAP_COUNT {
            let (start, end, mask) = EVENT_COUNTER_MAP[index];
            if event_idx >= start && event_idx <= end {
                return Some(mask);
            }
        }
    }
    None
}

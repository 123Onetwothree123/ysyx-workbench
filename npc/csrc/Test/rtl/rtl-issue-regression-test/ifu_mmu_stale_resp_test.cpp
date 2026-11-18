// 定向回归：取指 MMU 在 redirect/flush 后必须丢弃旧 walk 的迟到响应。
//
// 复现的核心场景：
//   1. 为旧 PC A 发起翻译；
//   2. 分支/陷阱 redirect 或 sfence.vma flush，旧请求失去意义；
//   3. IFU 为新 PC B 发起翻译；
//   4. 旧请求 A 的响应此时才到。MMUResponse 只带 tval(=请求 VA)，
//      若 IFUMMU 不校验 tval，就会把 A 的 PA 贴到 B 的标签上：
//      ICache 用新 PC 的快照取到旧物理行的指令字。
// 本测试要求：A 的迟到响应被丢弃；B 的响应才产生 translationValid。

#include "VIFUMMUHarness.h"
#include "test_common.hpp"

#include <cstdint>
#include <verilated.h>

using DUT = VIFUMMUHarness;

static constexpr uint64_t kSv39Satp = 0x8000000000000000ULL;
static constexpr uint64_t kOldVa = 0x00000000800080b0ULL;
static constexpr uint64_t kOldPa = 0x00000000800080b0ULL;
static constexpr uint64_t kNewVa = 0x0000000080003704ULL;
static constexpr uint64_t kNewPa = 0x0000000080003704ULL;

static void defaults(DUT &dut) {
  dut.io_fetchValid = 0;
  dut.io_fetchVaddr = 0;
  dut.io_cacheReady = 1;
  dut.io_pmpFault = 0;
  dut.io_privilege = 1; // S 模式，satp.MODE=Sv39 时必须走翻译
  dut.io_satp = kSv39Satp;
  dut.io_sum = 0;
  dut.io_mxr = 0;
  dut.io_flush = 0;
  dut.io_req_ready = 0;
  dut.io_resp_valid = 0;
  dut.io_resp_bits_id = 0;
  dut.io_resp_bits_paddr = 0;
  dut.io_resp_bits_fault = 0;
  dut.io_resp_bits_cause = 0;
  dut.io_resp_bits_tval = 0;
}

// 发起一笔翻译并接受 MMU 请求；返回时 IFUMMU 处于 xwait。
static void issue_request(DUT &dut, uint64_t vaddr) {
  dut.io_fetchValid = 1;
  dut.io_fetchVaddr = vaddr;
  wait_until(dut, [&] { return dut.io_req_valid; },
             "IFUMMU did not issue the translation request");
  check(dut.io_req_bits_vaddr == vaddr,
        "translation request lost its virtual address");
  check(dut.io_req_bits_id == 0, "fetch translation used a non-IFU id");
  dut.io_req_ready = 1;
  tick(dut);
  dut.io_req_ready = 0;
  dut.eval();
  check(!dut.io_req_valid, "IFUMMU issued more than one request per fetch");
}

static void send_response(DUT &dut, uint64_t vaddr, uint64_t paddr,
                          bool fault = false, unsigned cause = 0) {
  dut.io_resp_valid = 1;
  dut.io_resp_bits_id = 0;
  dut.io_resp_bits_paddr = paddr;
  dut.io_resp_bits_fault = fault;
  dut.io_resp_bits_cause = cause;
  dut.io_resp_bits_tval = vaddr;
  tick(dut);
  dut.io_resp_valid = 0;
  dut.io_resp_bits_tval = 0;
  dut.io_resp_bits_paddr = 0;
  dut.io_resp_bits_fault = 0;
  dut.io_resp_bits_cause = 0;
  dut.eval();
}

static void redirect(DUT &dut, uint64_t next_vaddr) {
  // 真实路径中 redirect/flush 会取消旧翻译；这里沿用同一公开 flush 语义。
  dut.io_flush = 1;
  tick(dut);
  dut.io_flush = 0;
  dut.io_fetchVaddr = next_vaddr;
  dut.io_fetchValid = 1;
  dut.eval();
}

static void stale_response_is_dropped_while_waiting_for_new_pc() {
  DUT dut;
  defaults(dut);
  reset(dut);

  issue_request(dut, kOldVa);
  redirect(dut, kNewVa);
  wait_until(dut, [&] { return dut.io_req_valid; },
             "new PC translation request never appeared");
  check(dut.io_req_bits_vaddr == kNewVa,
        "new PC translation request carried the stale virtual address");
  dut.io_req_ready = 1;
  tick(dut);
  dut.io_req_ready = 0;
  dut.eval();

  // 旧请求的迟到响应：VA/PA 都是旧 PC。绝不能把 kOldPa 当成 kNewVa 的翻译。
  send_response(dut, kOldVa, kOldPa);
  check(!dut.io_translationValid,
        "stale translation response was accepted as valid");
  check(dut.io_fetchPaddr != kOldPa,
        "stale translation PA leaked to the ICache fetch port");
  check(!dut.io_fault, "stale success response raised a fault");

  // 当前请求的响应仍然必须被正常接收。
  send_response(dut, kNewVa, kNewPa);
  check(dut.io_translationValid,
        "fresh translation response was not accepted after dropping stale one");
  check(dut.io_fetchPaddr == kNewPa,
        "fresh translation produced the wrong physical address");
  check(dut.io_fetchPaddrVaddr == kNewVa,
        "fresh translation PA is tagged with the wrong virtual address");
  check(dut.io_fetchReady, "translationValid did not propagate to fetchReady");
}

static void stale_fault_response_must_not_raise_page_fault() {
  DUT dut;
  defaults(dut);
  reset(dut);

  issue_request(dut, kOldVa);
  redirect(dut, kNewVa);
  wait_until(dut, [&] { return dut.io_req_valid; },
             "new PC translation request never appeared");
  dut.io_req_ready = 1;
  tick(dut);
  dut.io_req_ready = 0;
  dut.eval();

  // 旧请求的 page fault 不能以新 PC 的身份提交。
  send_response(dut, kOldVa, 0, true, 12);
  check(!dut.io_fault, "stale fault response raised a page fault");
  check(!dut.io_translationValid,
        "stale fault response produced a translation");

  send_response(dut, kNewVa, 0, true, 13);
  check(dut.io_fault, "fresh fault response was not accepted");
  check(dut.io_faultCause == 13, "fresh fault response lost its cause");
  check(dut.io_faultVaddr == kNewVa, "fresh fault reported the wrong tval");
  check(!dut.io_translationValid, "fault response was reported as valid");
}

static void late_response_after_done_cannot_overwrite_translation() {
  DUT dut;
  defaults(dut);
  reset(dut);

  issue_request(dut, kOldVa);
  redirect(dut, kNewVa);
  wait_until(dut, [&] { return dut.io_req_valid; },
             "new PC translation request never appeared");
  dut.io_req_ready = 1;
  tick(dut);
  dut.io_req_ready = 0;
  dut.eval();

  // 保持 cacheReady=0，让已完成的翻译停留在 done 状态，模拟 ICache 尚未
  // 消费；此时旧响应不能改写 paddrReg/paddrVaddrReg。
  dut.io_cacheReady = 0;
  send_response(dut, kNewVa, kNewPa);
  check(dut.io_translationValid && dut.io_fetchPaddr == kNewPa,
        "fresh translation did not become visible");

  // done 状态下仍可能收到旧响应；它只能被排空，不能改写已完成的翻译。
  send_response(dut, kOldVa, kOldPa);
  check(dut.io_translationValid,
        "late stale response cancelled a completed translation");
  check(dut.io_fetchPaddr == kNewPa,
        "late stale response overwrote the completed PA");
  check(dut.io_fetchPaddrVaddr == kNewVa,
        "late stale response overwrote the translation VA tag");
}

int main(int argc, char **argv) {
  Verilated::commandArgs(argc, argv);
  int failures = 0;
  failures += run_test("IFUMMU drops stale response while waiting for new PC",
                       stale_response_is_dropped_while_waiting_for_new_pc);
  failures += run_test("IFUMMU drops stale fault response",
                       stale_fault_response_must_not_raise_page_fault);
  failures += run_test("IFUMMU ignores late response after done",
                       late_response_after_done_cannot_overwrite_translation);
  return failures == 0 ? 0 : 1;
}

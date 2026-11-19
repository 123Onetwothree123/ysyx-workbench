#pragma once
#include <algorithm>
#include <atomic>
#include <condition_variable>
#include <cstddef>
#include <deque>
#include <exception>
#include <functional>
#include <mutex>
#include <thread>
#include <utility>
#include <vector>
namespace common
{
class ThreadPool
{
public:
    explicit ThreadPool(std::size_t workers = 0, std::size_t max_queue = 0)
    {
        if (workers == 0)
            workers = HardwareConcurrency();
        max_queue_ = max_queue != 0 ? max_queue : std::max<std::size_t>(64, workers * 16);
        workers_.reserve(workers);
        for (std::size_t i{0}; i < workers; ++i)
            workers_.emplace_back([this](std::stop_token token) { WorkerLoop(token); });
    }
    ~ThreadPool() { Stop(); }
    ThreadPool(const ThreadPool&) = delete;
    ThreadPool& operator=(const ThreadPool&) = delete;
    void Submit(std::function<void()> task)
    {
        std::unique_lock lock{mutex_};
        space_.wait(lock, [this]
                    { return stop_.load(std::memory_order_relaxed) || queue_.size() < max_queue_; });
        if (stop_.load(std::memory_order_relaxed))
            return;
        queue_.push_back(std::move(task));
        ready_.notify_one();
    }
    // 等队列清空且所有任务结束；任务异常在这里重抛
    void Wait()
    {
        std::unique_lock lock{mutex_};
        idle_.wait(lock, [this] { return queue_.empty() && active_ == 0; });
        if (error_)
        {
            auto error{std::move(error_)};
            error_ = nullptr;
            lock.unlock();
            std::rethrow_exception(error);
        }
    }
    void Stop()
    {
        stop_.store(true, std::memory_order_relaxed);
        ready_.notify_all();
        space_.notify_all();
        for (auto& worker : workers_)
            worker.request_stop();
        workers_.clear(); // jthread 析构 join
        std::lock_guard lock{mutex_};
        queue_.clear();
    }
    [[nodiscard]] auto WorkerCount() const -> std::size_t { return workers_.size(); }
    static auto HardwareConcurrency() -> std::size_t
    {
        return std::max<std::size_t>(1, std::thread::hardware_concurrency());
    }
private:
    void WorkerLoop(std::stop_token token)
    {
        for (;;)
        {
            std::function<void()> task{};
            {
                std::unique_lock lock{mutex_};
                ready_.wait(lock, [this, &token]
                            {
                                return stop_.load(std::memory_order_relaxed)
                                    || token.stop_requested() || !queue_.empty();
                            });
                if (stop_.load(std::memory_order_relaxed) || token.stop_requested())
                    return;
                task = std::move(queue_.front());
                queue_.pop_front();
                ++active_;
            }
            space_.notify_one();
            try
            {
                task();
            }
            catch (...)
            {
                std::lock_guard lock{mutex_};
                if (!error_)
                    error_ = std::current_exception();
            }
            {
                std::lock_guard lock{mutex_};
                --active_;
            }
            idle_.notify_all();
        }
    }
    std::vector<std::jthread> workers_{};
    std::deque<std::function<void()>> queue_{};
    std::mutex mutex_{};
    std::condition_variable ready_{};
    std::condition_variable space_{};
    std::condition_variable idle_{};
    std::atomic<bool> stop_{false};
    std::size_t active_{};
    std::size_t max_queue_{};
    std::exception_ptr error_{};
};
// 并行执行 fn(0 .. n-1)，任一任务异常在全部结束后重抛
template <typename Fn>
void ParallelFor(std::size_t n, Fn fn)
{
    if (n == 0)
        return;
    const auto workers{std::min(ThreadPool::HardwareConcurrency(), n)};
    ThreadPool pool{workers};
    for (std::size_t i{0}; i < n; ++i)
        pool.Submit([&fn, i] { fn(i); });
    pool.Wait();
}
}

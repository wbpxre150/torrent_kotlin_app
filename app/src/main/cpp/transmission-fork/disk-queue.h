// ANDROID fork: TRANSMISSION_USB_SAF disk worker queue. std-only so it can be host-tested.
#pragma once
#include <chrono>
#include <condition_variable>
#include <deque>
#include <functional>
#include <mutex>
#include <thread>

class tr_disk_queue
{
public:
    enum class Prio { High, Low };
    using Job = std::function<void()>;

    explicit tr_disk_queue(std::function<void()> on_thread_start = {});
    ~tr_disk_queue(); // calls stop_and_discard() if still running

    void push(Prio prio, Job job);           // thread-safe; ignored after stop
    void stop_and_discard();                 // finish current job, drop queued, join
    void stop_and_drain(std::chrono::steady_clock::time_point deadline);
    [[nodiscard]] size_t size() const;       // queued (not running) jobs, both queues
private:
    void run();
    mutable std::mutex mutex_;
    std::condition_variable cv_;
    std::deque<Job> high_, low_;
    bool stopping_ = false;
    bool draining_ = false;
    std::chrono::steady_clock::time_point deadline_{};
    std::thread thread_; // started in the constructor, last member
};

inline tr_disk_queue::tr_disk_queue(std::function<void()> on_thread_start)
    : thread_([this, on_start = std::move(on_thread_start)]()
              {
                  // Called once on the worker thread, before the loop (used to name the thread).
                  if (on_start)
                  {
                      on_start();
                  }
                  run();
              })
{
}

inline tr_disk_queue::~tr_disk_queue()
{
    stop_and_discard();
}

inline void tr_disk_queue::push(Prio prio, Job job)
{
    std::lock_guard<std::mutex> const lock{ mutex_ };
    if (stopping_)
    {
        return; // ignored after stop
    }
    (prio == Prio::High ? high_ : low_).push_back(std::move(job));
    cv_.notify_one();
}

inline void tr_disk_queue::stop_and_discard()
{
    {
        std::lock_guard<std::mutex> const lock{ mutex_ };
        if (stopping_)
        {
            return; // idempotent
        }
        stopping_ = true;
        draining_ = false;
        high_.clear();
        low_.clear();
    }
    cv_.notify_all();
    if (thread_.joinable())
    {
        thread_.join();
    }
}

inline void tr_disk_queue::stop_and_drain(std::chrono::steady_clock::time_point deadline)
{
    {
        std::lock_guard<std::mutex> const lock{ mutex_ };
        if (stopping_)
        {
            return; // idempotent
        }
        stopping_ = true;
        draining_ = true;
        deadline_ = deadline;
    }
    cv_.notify_all();
    if (thread_.joinable())
    {
        thread_.join();
    }
}

inline size_t tr_disk_queue::size() const
{
    std::lock_guard<std::mutex> const lock{ mutex_ };
    return high_.size() + low_.size();
}

inline void tr_disk_queue::run()
{
    std::unique_lock<std::mutex> lock{ mutex_ };
    for (;;)
    {
        cv_.wait(lock, [this]
                 { return !high_.empty() || !low_.empty() || stopping_; });
        if (stopping_ && !draining_)
        {
            return; // discard everything
        }
        if (draining_ && ((high_.empty() && low_.empty()) || std::chrono::steady_clock::now() >= deadline_))
        {
            return; // drained, or the deadline is past: drop the rest
        }
        Job job;
        if (!high_.empty())
        {
            job = std::move(high_.front());
            high_.pop_front();
        }
        else
        {
            job = std::move(low_.front());
            low_.pop_front();
        }
        lock.unlock();
        try
        {
            job();
        }
        catch (...)
        {
            // jobs must not throw; swallow anyway
        }
        lock.lock();
    }
}

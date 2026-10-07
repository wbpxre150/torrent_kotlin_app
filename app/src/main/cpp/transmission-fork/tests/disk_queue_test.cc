// ANDROID fork: host unit test for disk-queue.h (TRANSMISSION_USB_SAF stage 1, step 1.2).
// Plain main() with asserts, no framework. Must be compiled without -DNDEBUG. Run via run.sh.
#include <atomic>
#include <cassert>
#include <chrono>
#include <cstdio>
#include <future>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "disk-queue.h"

int main()
{
    // 1. FIFO order within Low: 100 jobs appending ints, checked in order.
    {
        tr_disk_queue q;
        std::mutex m;
        std::vector<int> got;
        for (int i = 0; i < 100; ++i)
        {
            q.push(tr_disk_queue::Prio::Low, [&, i]
                   {
                       std::lock_guard<std::mutex> const lock{ m };
                       got.push_back(i);
                   });
        }
        // Barrier job: by FIFO it runs only after all 100, so this blocks until they are done.
        std::promise<void> done;
        auto fut = done.get_future();
        q.push(tr_disk_queue::Prio::Low, [&done]
               { done.set_value(); });
        fut.get();
        assert(got.size() == 100U);
        for (int i = 0; i < 100; ++i)
        {
            assert(got[i] == i);
        }
    }

    // 2. High before Low: block the worker, push L1, L2, H1, release; expect H1, L1, L2.
    {
        tr_disk_queue q;
        std::mutex m;
        std::vector<std::string> order;
        auto const append = [&m, &order](std::string const& name)
        {
            std::lock_guard<std::mutex> const lock{ m };
            order.push_back(name);
        };
        std::promise<void> started;
        auto started_fut = started.get_future();
        std::promise<void> release;
        auto release_fut = release.get_future();
        // Blocks the worker until released.
        q.push(tr_disk_queue::Prio::Low, [&started, &release_fut]
               {
                   started.set_value();
                   release_fut.get();
               });
        started_fut.get(); // the blocking job has started; the worker is stuck in it
        q.push(tr_disk_queue::Prio::Low, [&append] { append("L1"); });
        q.push(tr_disk_queue::Prio::Low, [&append] { append("L2"); });
        q.push(tr_disk_queue::Prio::High, [&append] { append("H1"); });
        release.set_value();
        std::promise<void> done;
        auto fut = done.get_future();
        q.push(tr_disk_queue::Prio::Low, [&done]
               { done.set_value(); });
        fut.get();
        assert(order.size() == 3U);
        assert(order[0] == "H1");
        assert(order[1] == "L1");
        assert(order[2] == "L2");
    }

    // 3. stop_and_discard() drops queued jobs: blocked first job, 10 queued,
    // discard, release; at most the blocked job ran.
    {
        tr_disk_queue q;
        std::atomic<int> ran{ 0 };
        std::promise<void> started;
        auto started_fut = started.get_future();
        std::promise<void> release;
        auto release_fut = release.get_future();
        q.push(tr_disk_queue::Prio::Low, [&]
               {
                   started.set_value();
                   release_fut.get();
                   ran.fetch_add(1, std::memory_order_relaxed);
               });
        started_fut.get(); // the worker is blocked in the first job
        for (int i = 0; i < 10; ++i)
        {
            q.push(tr_disk_queue::Prio::Low, [&ran]
                   { ran.fetch_add(1, std::memory_order_relaxed); });
        }
        // stop_and_discard() joins, so run it on a helper thread.
        std::thread stopper([&q] { q.stop_and_discard(); });
        // Wait until the 10 queued jobs have been dropped (the running job is not counted
        // by size()). Only then is it safe to release the blocked job.
        while (q.size() != 0U)
        {
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
        release.set_value(); // the blocked job finishes, then the worker exits
        stopper.join();
        assert(ran.load(std::memory_order_relaxed) == 1); // at most the blocked job ran
    }

    // 4a. stop_and_drain(far deadline) runs all queued jobs.
    {
        tr_disk_queue q;
        std::atomic<int> ran{ 0 };
        for (int i = 0; i < 10; ++i)
        {
            q.push(tr_disk_queue::Prio::Low, [&ran]
                   { ran.fetch_add(1, std::memory_order_relaxed); });
        }
        q.stop_and_drain(std::chrono::steady_clock::now() + std::chrono::seconds(60));
        assert(ran.load(std::memory_order_relaxed) == 10);
    }

    // 4b. stop_and_drain(past deadline) runs none of the queued jobs.
    {
        tr_disk_queue q;
        std::atomic<int> ran{ 0 };
        std::promise<void> started;
        auto started_fut = started.get_future();
        std::promise<void> release;
        auto release_fut = release.get_future();
        q.push(tr_disk_queue::Prio::Low, [&]
               {
                   started.set_value();
                   release_fut.get();
               });
        started_fut.get(); // the worker is blocked in the first job
        for (int i = 0; i < 10; ++i)
        {
            q.push(tr_disk_queue::Prio::Low, [&ran]
                   { ran.fetch_add(1, std::memory_order_relaxed); });
        }
        // stop_and_drain() joins, so run it on a helper thread.
        std::thread drainer([&q]
                            { q.stop_and_drain(std::chrono::steady_clock::now() - std::chrono::seconds(1)); });
        // Push more jobs until the stop flag set by stop_and_drain() rejects one. The worker
        // is still blocked, so nothing queued can have run, and once a push is rejected the
        // deadline check is guaranteed to fire when the worker is released.
        for (int i = 0; i < 1000; ++i)
        {
            auto const before = q.size();
            q.push(tr_disk_queue::Prio::Low, [&ran]
                   { ran.fetch_add(1, std::memory_order_relaxed); });
            if (q.size() == before)
            {
                break; // push ignored: stopping is set
            }
        }
        release.set_value();
        drainer.join();
        assert(ran.load(std::memory_order_relaxed) == 0);
    }

    std::printf("success\n");
    return 0;
}

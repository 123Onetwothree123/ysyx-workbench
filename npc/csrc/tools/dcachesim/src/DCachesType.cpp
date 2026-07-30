module DCachesType;
import std;
import DCache;
import DCacheConfig;

DCachesType::DCachesType(const DCacheConfig& config)
{
    caches.push_back(std::make_unique<DCache>(config, ReplPolicy::FIFO, "FIFO"));
    caches.push_back(std::make_unique<DCache>(config, ReplPolicy::LRU, "LRU"));
    caches.push_back(std::make_unique<DCache>(config, ReplPolicy::Random, "Random"));
}
auto DCachesType::begin() const noexcept
{
    return caches.begin();
}
auto DCachesType::end() const noexcept
{
    return caches.end();
}
std::size_t DCachesType::size() const noexcept
{
    return caches.size();
}
DCache& DCachesType::operator[](std::size_t i) const
{
    return *caches[i];
}

export module DCachesType;
import std;
import DCache;
import DCacheConfig;
export class DCachesType
{
private:
    std::vector<std::unique_ptr<DCache>> caches;

public:
    explicit DCachesType(const DCacheConfig& config);
    ~DCachesType() = default;
    DCachesType(DCachesType&&) noexcept = default;
    DCachesType& operator=(DCachesType&&) noexcept = default;
    DCachesType(const DCachesType&) = delete;
    DCachesType& operator=(const DCachesType&) = delete;
    [[nodiscard]] std::size_t size() const noexcept;
    DCache& operator[](std::size_t i) const;
};

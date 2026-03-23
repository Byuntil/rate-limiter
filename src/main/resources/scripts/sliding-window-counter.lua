-- Sliding Window Counter Rate Limiter
-- 원자적 실행으로 GET→계산→INCR 사이의 경쟁 조건을 제거한다
--
-- KEYS[1]: 현재 윈도우 Redis key
-- KEYS[2]: 이전 윈도우 Redis key
-- ARGV[1]: limit (최대 허용 횟수)
-- ARGV[2]: windowSeconds (윈도우 크기, 초 단위)
-- ARGV[3]: currentSecond (현재 윈도우 내 경과 초)
--
-- 반환값:
--   -1: 차단 (가중 평균 >= limit)
--   N (양수): 허용 (현재 윈도우의 새 카운트)

local current_key = KEYS[1]
local previous_key = KEYS[2]
local limit = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local current_second = tonumber(ARGV[3])

local previous_count = tonumber(redis.call('GET', previous_key) or "0")
local current_count = tonumber(redis.call('GET', current_key) or "0")

-- 가중 평균 = 이전 윈도우 카운트 × (1 - 경과 비율) + 현재 윈도우 카운트
local elapsed_ratio = current_second / window
local weighted_count = previous_count * (1 - elapsed_ratio) + current_count

if weighted_count >= limit then
    return -1
end

local new_count = redis.call('INCR', current_key)
if new_count == 1 then
    redis.call('EXPIRE', current_key, window * 2)
end

return new_count

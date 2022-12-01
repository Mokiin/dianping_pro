if (ARGV[1] == redis.call('get', KEYS[1])) then
    redis.call('del', KEYS[1])
end
return 0;
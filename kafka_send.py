# encoding: utf-8

from kafka import KafkaProducer
import json


def generate_data():
    data = dict()
    data['date'] = '2018-08-28'
    data['referer'] = 'https://biz.finance.sina.com.cn/zjzt/list.php?zjztType=forexask&answerType=1&t=forex_new'
    data['country'] = '中国'
    data['city'] = '北京'
    data['province'] = '北京'
    data['local'] = '127.0.0.1'
    data['datetime'] = '2018-08-28 03:35:50'
    data['request_time'] = 0.018
    data['upstream_ip'] = 'localhost:80'
    data['user_agent'] = 'Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/63.0.3239.132 Safari/537.36'
    data['remote_addr'] = '202.110.105.30'
    data['method'] = 'GET'
    data['pool'] = 'finance2'
    data['session_id'] = '-'
    data['url'] = '/basketball/nba/2018-08-28/doc-ihiixyeu0498612.shtml'
    data['body_bytes_send'] = 19325
    data['body_bytes_receive'] = 1216
    data['domain'] = 'sports.sina.com.cn'
    data['http_ver'] = 'HTTP/1.1'
    data['status'] = '200'

    return json.dumps(data)


if __name__ == '__main__':
    str = generate_data()
    p = KafkaProducer(bootstrap_servers="localhost:9092")
    while True:
        p.send('waterdrop_benchmark_input', str)

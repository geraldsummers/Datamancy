"""
Unit tests for Hyperliquid Worker
"""
import pytest
import sys
from pathlib import Path
from unittest.mock import Mock, patch, MagicMock
from decimal import Decimal

# Add app to path
sys.path.insert(0, str(Path(__file__).parent.parent / "app"))

import main


class TestParseHyperliquidKey:
    """Test Hyperliquid API key parsing"""

    def test_parse_key_with_address(self):
        """Test parsing key with address:private_key format"""
        api_key = "0xAddress123:privatekey456"
        result = main.parse_hyperliquid_key(api_key)
        assert result['address'] == "0xAddress123"
        assert result['private_key'] == "privatekey456"

    def test_parse_key_without_address(self):
        """Test parsing key with only private key"""
        api_key = "privatekey123"
        result = main.parse_hyperliquid_key(api_key)
        assert result['address'] is None
        assert result['private_key'] == "privatekey123"

    def test_parse_key_with_multiple_colons(self):
        """Test parsing key with multiple colons"""
        api_key = "0xAddress:privatekey:withcolon"
        result = main.parse_hyperliquid_key(api_key)
        assert result['address'] == "0xAddress"
        assert result['private_key'] == "privatekey:withcolon"

    def test_parse_key_rejects_empty(self):
        with pytest.raises(ValueError, match="hyperliquidKey is empty"):
            main.parse_hyperliquid_key("  ")


class TestAddressResolution:
    @patch('main.Account')
    def test_resolve_account_address_derives_from_private_key(self, mock_account):
        mock_account.from_key.return_value = Mock(address="0xDerived")
        creds = {"address": None, "private_key": "abcdef1234"}
        assert main.resolve_account_address(creds) == "0xDerived"
        mock_account.from_key.assert_called_once_with("0xabcdef1234")

    def test_resolve_account_address_prefers_explicit_address(self):
        creds = {"address": "0xExplicit", "private_key": "unused"}
        assert main.resolve_account_address(creds) == "0xExplicit"


class TestGetExchangeClient:
    """Test Exchange client initialization"""

    @patch('main.Exchange')
    def test_get_exchange_client_mainnet(self, mock_exchange_class):
        """Test getting Exchange client for mainnet"""
        with patch('main.IS_MAINNET', True):
            hyperliquid_key = "0xAddress:privatekey"
            client = main.get_exchange_client(hyperliquid_key)

            mock_exchange_class.assert_called_once()
            call_kwargs = mock_exchange_class.call_args.kwargs
            assert call_kwargs['address'] == "0xAddress"
            assert call_kwargs['private_key'] == "privatekey"
            assert call_kwargs['skip_ws'] is True

    @patch('main.Exchange')
    def test_get_exchange_client_testnet(self, mock_exchange_class):
        """Test getting Exchange client for testnet"""
        with patch('main.IS_MAINNET', False):
            hyperliquid_key = "privatekey123"
            client = main.get_exchange_client(hyperliquid_key)

            mock_exchange_class.assert_called_once()
            call_kwargs = mock_exchange_class.call_args.kwargs
            assert call_kwargs['private_key'] == "privatekey123"
            assert call_kwargs['skip_ws'] is True


class TestGetInfoClient:
    """Test Info client initialization"""

    @patch('main.Info')
    def test_get_info_client_mainnet(self, mock_info_class):
        """Test getting Info client for mainnet"""
        with patch('main.IS_MAINNET', True):
            client = main.get_info_client()
            mock_info_class.assert_called_once()
            call_kwargs = mock_info_class.call_args.kwargs
            assert call_kwargs['skip_ws'] is True

    @patch('main.Info')
    def test_get_info_client_testnet(self, mock_info_class):
        """Test getting Info client for testnet"""
        with patch('main.IS_MAINNET', False):
            client = main.get_info_client()
            mock_info_class.assert_called_once()
            call_kwargs = mock_info_class.call_args.kwargs
            assert call_kwargs['skip_ws'] is True


class TestFlaskEndpoints:
    """Test Flask API endpoints"""

    @pytest.fixture
    def client(self):
        """Create test client"""
        main.app.config['TESTING'] = True
        main.WORKER_SHARED_TOKEN = "test-worker-token"
        with main.app.test_client() as client:
            client.environ_base["HTTP_X_WORKER_TOKEN"] = "test-worker-token"
            yield client

    @patch('main.get_info_client')
    def test_health_endpoint_healthy(self, mock_get_info, client):
        """Test health check endpoint when healthy"""
        mock_info = Mock()
        mock_info.meta.return_value = {'status': 'ok'}
        mock_get_info.return_value = mock_info

        response = client.get('/health')
        assert response.status_code == 200
        data = response.get_json()
        assert data['service'] == 'hyperliquid-worker'
        assert 'mainnet' in data

    @patch('main.get_info_client')
    def test_health_endpoint_degraded(self, mock_get_info, client):
        """Test health check endpoint when degraded"""
        mock_get_info.side_effect = Exception("Connection failed")

        response = client.get('/health')
        assert response.status_code == 200
        data = response.get_json()
        assert data['status'] == 'degraded'

    def test_order_missing_key(self, client):
        """Test order submission without API key"""
        response = client.post('/order', json={
            'username': 'testuser',
            'symbol': 'BTC',
            'side': 'BUY',
            'type': 'MARKET',
            'size': '0.1'
        })
        assert response.status_code == 400
        data = response.get_json()
        assert 'hyperliquidKey' in data['error']

    @patch('main.get_info_client')
    @patch('main.get_exchange_client')
    def test_order_market_success(self, mock_get_exchange, mock_get_info, client):
        """Test successful market order"""
        mock_info = Mock()
        mock_info.all_mids.return_value = {'BTC': '50000.0'}
        mock_get_info.return_value = mock_info

        mock_exchange = Mock()
        mock_exchange.market_order.return_value = {
            'status': 'ok',
            'response': {
                'data': {
                    'statuses': [{
                        'status': 'FILLED',
                        'filled': {
                            'oid': 12345,
                            'px': '50000.0'
                        }
                    }]
                }
            }
        }
        mock_get_exchange.return_value = mock_exchange

        response = client.post('/order', json={
            'username': 'testuser',
            'symbol': 'BTC',
            'side': 'BUY',
            'type': 'MARKET',
            'size': '0.1',
            'hyperliquidKey': 'testkey'
        })

        assert response.status_code == 200
        data = response.get_json()
        assert data['symbol'] == 'BTC'
        assert data['side'] == 'BUY'
        assert data['orderId'] == '12345'

    @patch('main.get_exchange_client')
    def test_order_limit_success(self, mock_get_exchange, client):
        """Test successful limit order"""
        mock_exchange = Mock()
        mock_exchange.order.return_value = {
            'status': 'ok',
            'response': {
                'data': {
                    'statuses': [{
                        'status': 'OPEN',
                        'filled': {
                            'oid': 54321,
                            'px': '49000.0'
                        }
                    }]
                }
            }
        }
        mock_get_exchange.return_value = mock_exchange

        response = client.post('/order', json={
            'username': 'testuser',
            'symbol': 'BTC',
            'side': 'SELL',
            'type': 'LIMIT',
            'size': '0.1',
            'price': '51000.0',
            'hyperliquidKey': 'testkey'
        })

        assert response.status_code == 200
        data = response.get_json()
        assert data['symbol'] == 'BTC'
        assert data['side'] == 'SELL'
        assert data['type'] == 'LIMIT'

    def test_order_limit_missing_price(self, client):
        """Test limit order without price"""
        with patch('main.get_exchange_client'):
            response = client.post('/order', json={
                'username': 'testuser',
                'symbol': 'BTC',
                'side': 'BUY',
                'type': 'LIMIT',
                'size': '0.1',
                'hyperliquidKey': 'testkey'
            })
            assert response.status_code == 400
            data = response.get_json()
            assert 'Price required' in data['error']

    def test_order_rejects_invalid_side(self, client):
        response = client.post('/order', json={
            'username': 'testuser',
            'symbol': 'BTC',
            'side': 'HOLD',
            'type': 'MARKET',
            'size': '0.1',
            'hyperliquidKey': 'testkey'
        })
        assert response.status_code == 400
        assert 'Unsupported side' in response.get_json()['error']

    def test_order_rejects_non_positive_size(self, client):
        response = client.post('/order', json={
            'username': 'testuser',
            'symbol': 'BTC',
            'side': 'BUY',
            'type': 'MARKET',
            'size': '-1',
            'hyperliquidKey': 'testkey'
        })
        assert response.status_code == 400
        assert 'size must be > 0' in response.get_json()['error']

    @patch('main.get_exchange_client')
    def test_order_rejects_unreasonable_slippage(self, mock_get_exchange, client):
        response = client.post('/order', json={
            'username': 'testuser',
            'symbol': 'BTC',
            'side': 'BUY',
            'type': 'MARKET',
            'size': '0.1',
            'maxSlippageBps': '900',
            'hyperliquidKey': 'testkey'
        })
        assert response.status_code == 400
        assert 'maxSlippageBps is unreasonably high' in response.get_json()['error']

    def test_order_rejects_post_only_market_order(self, client):
        response = client.post('/order', json={
            'username': 'testuser',
            'symbol': 'BTC',
            'side': 'BUY',
            'type': 'MARKET',
            'size': '0.1',
            'postOnly': True,
            'hyperliquidKey': 'testkey'
        })
        assert response.status_code == 400
        assert 'postOnly is only valid for LIMIT orders' in response.get_json()['error']

    @patch('main.get_exchange_client')
    def test_order_rejects_too_fast_cancel(self, mock_get_exchange, client):
        response = client.post('/order', json={
            'username': 'testuser',
            'symbol': 'BTC',
            'side': 'BUY',
            'type': 'MARKET',
            'size': '0.1',
            'cancelAfterMs': 50,
            'hyperliquidKey': 'testkey'
        })
        assert response.status_code == 400
        assert 'cancelAfterMs must be >= 100' in response.get_json()['error']

    def test_order_rejects_too_slow_cancel(self, client):
        response = client.post('/order', json={
            'username': 'testuser',
            'symbol': 'BTC',
            'side': 'BUY',
            'type': 'MARKET',
            'size': '0.1',
            'cancelAfterMs': 600001,
            'hyperliquidKey': 'testkey'
        })
        assert response.status_code == 400
        assert 'cancelAfterMs must be <= 600000' in response.get_json()['error']

    @patch('main.get_info_client')
    @patch('main.get_exchange_client')
    def test_market_order_with_slippage_uses_ioc_limit(self, mock_get_exchange, mock_get_info, client):
        mock_info = Mock()
        mock_info.all_mids.return_value = {'BTC': '50000.0'}
        mock_get_info.return_value = mock_info

        mock_exchange = Mock()
        mock_exchange.order.return_value = {
            'status': 'ok',
            'response': {
                'data': {
                    'statuses': [{
                        'status': 'FILLED',
                        'filled': {
                            'oid': 321,
                            'px': '50010.0',
                            'totalSz': '0.1'
                        }
                    }]
                }
            }
        }
        mock_get_exchange.return_value = mock_exchange

        response = client.post('/order', json={
            'username': 'testuser',
            'symbol': 'BTC',
            'side': 'BUY',
            'type': 'MARKET',
            'size': '0.1',
            'maxSlippageBps': '5',
            'hyperliquidKey': 'testkey'
        })

        assert response.status_code == 200
        call_kwargs = mock_exchange.order.call_args.kwargs
        assert call_kwargs['order_type'] == {'limit': {'tif': 'Ioc'}}
        assert call_kwargs['is_buy'] is True
        assert float(call_kwargs['limit_px']) > 50000.0

    @patch('main.get_exchange_client')
    def test_limit_order_post_only_uses_alo_tif(self, mock_get_exchange, client):
        mock_exchange = Mock()
        mock_exchange.order.return_value = {
            'status': 'ok',
            'response': {
                'data': {
                    'statuses': [{
                        'status': 'OPEN',
                        'filled': {
                            'oid': 991,
                            'px': '50000.0',
                            'totalSz': '0'
                        }
                    }]
                }
            }
        }
        mock_get_exchange.return_value = mock_exchange

        response = client.post('/order', json={
            'username': 'testuser',
            'symbol': 'BTC',
            'side': 'BUY',
            'type': 'LIMIT',
            'size': '0.1',
            'price': '50000.0',
            'postOnly': True,
            'hyperliquidKey': 'testkey'
        })

        assert response.status_code == 200
        call_kwargs = mock_exchange.order.call_args.kwargs
        assert call_kwargs['order_type'] == {'limit': {'tif': 'Alo'}}

    @patch('main.get_info_client')
    @patch('main.get_exchange_client')
    def test_limit_order_rejects_when_buy_price_exceeds_slippage_guard(self, mock_get_exchange, mock_get_info, client):
        mock_info = Mock()
        mock_info.all_mids.return_value = {'BTC': '50000.0'}
        mock_get_info.return_value = mock_info

        response = client.post('/order', json={
            'username': 'testuser',
            'symbol': 'BTC',
            'side': 'BUY',
            'type': 'LIMIT',
            'size': '0.1',
            'price': '50500.0',
            'maxSlippageBps': '5',
            'hyperliquidKey': 'testkey'
        })

        assert response.status_code == 400
        assert 'Limit price exceeds slippage guard' in response.get_json()['error']
        mock_get_exchange.assert_not_called()

    @patch('main.get_info_client')
    @patch('main.get_exchange_client')
    def test_limit_order_rejects_when_sell_price_exceeds_slippage_guard(self, mock_get_exchange, mock_get_info, client):
        mock_info = Mock()
        mock_info.all_mids.return_value = {'BTC': '50000.0'}
        mock_get_info.return_value = mock_info

        response = client.post('/order', json={
            'username': 'testuser',
            'symbol': 'BTC',
            'side': 'SELL',
            'type': 'LIMIT',
            'size': '0.1',
            'price': '49500.0',
            'maxSlippageBps': '5',
            'hyperliquidKey': 'testkey'
        })

        assert response.status_code == 400
        assert 'Limit price exceeds slippage guard' in response.get_json()['error']
        mock_get_exchange.assert_not_called()

    @patch('main.get_exchange_client')
    def test_limit_order_rejects_notional_over_limit(self, mock_get_exchange, client):
        with patch.object(main, 'MAX_ORDER_NOTIONAL_USD', Decimal('1000')):
            response = client.post('/order', json={
                'username': 'testuser',
                'symbol': 'BTC',
                'side': 'BUY',
                'type': 'LIMIT',
                'size': '1',
                'price': '5000',
                'hyperliquidKey': 'testkey'
            })

        assert response.status_code == 400
        assert 'Order notional exceeds max allowed' in response.get_json()['error']

    @patch('main.get_exchange_client')
    def test_limit_order_forwards_reduce_only_when_supported(self, mock_get_exchange, client):
        class ExchangeStub:
            def __init__(self):
                self.called_kwargs = None

            def order(self, symbol, is_buy, sz, limit_px, order_type, reduce_only=False):
                self.called_kwargs = {
                    "symbol": symbol,
                    "is_buy": is_buy,
                    "sz": sz,
                    "limit_px": limit_px,
                    "order_type": order_type,
                    "reduce_only": reduce_only,
                }
                return {
                    'status': 'ok',
                    'response': {
                        'data': {
                            'statuses': [{
                                'status': 'OPEN',
                                'resting': {'oid': 777}
                            }]
                        }
                    }
                }

        exchange = ExchangeStub()
        mock_get_exchange.return_value = exchange

        response = client.post('/order', json={
            'username': 'testuser',
            'symbol': 'BTC',
            'side': 'SELL',
            'type': 'LIMIT',
            'size': '0.1',
            'price': '50000.0',
            'reduceOnly': True,
            'hyperliquidKey': 'testkey'
        })

        assert response.status_code == 200
        assert exchange.called_kwargs is not None
        assert exchange.called_kwargs['reduce_only'] is True
        assert response.get_json()['reduceOnly'] is True

    @patch('main.get_info_client')
    @patch('main.get_exchange_client')
    def test_market_order_forwards_reduce_only_when_supported(self, mock_get_exchange, mock_get_info, client):
        class ExchangeStub:
            def __init__(self):
                self.called_kwargs = None

            def market_order(self, symbol, is_buy, sz, reduce_only=False):
                self.called_kwargs = {
                    "symbol": symbol,
                    "is_buy": is_buy,
                    "sz": sz,
                    "reduce_only": reduce_only,
                }
                return {
                    'status': 'ok',
                    'response': {
                        'data': {
                            'statuses': [{
                                'status': 'FILLED',
                                'filled': {'oid': 778, 'px': '50000.0', 'totalSz': '0.1'}
                            }]
                        }
                    }
                }

        mock_info = Mock()
        mock_info.all_mids.return_value = {'BTC': '50000.0'}
        mock_get_info.return_value = mock_info

        exchange = ExchangeStub()
        mock_get_exchange.return_value = exchange

        response = client.post('/order', json={
            'username': 'testuser',
            'symbol': 'BTC',
            'side': 'BUY',
            'type': 'MARKET',
            'size': '0.1',
            'reduceOnly': True,
            'hyperliquidKey': 'testkey'
        })

        assert response.status_code == 200
        assert exchange.called_kwargs is not None
        assert exchange.called_kwargs['reduce_only'] is True
        assert response.get_json()['reduceOnly'] is True

    @patch('main.get_exchange_client')
    def test_order_parses_resting_oid_and_pending_status(self, mock_get_exchange, client):
        mock_exchange = Mock()
        mock_exchange.order.return_value = {
            'status': 'ok',
            'response': {
                'data': {
                    'statuses': [{
                        'resting': {'oid': 445566}
                    }]
                }
            }
        }
        mock_get_exchange.return_value = mock_exchange

        response = client.post('/order', json={
            'username': 'testuser',
            'symbol': 'BTC',
            'side': 'BUY',
            'type': 'LIMIT',
            'size': '0.1',
            'price': '50000.0',
            'hyperliquidKey': 'testkey'
        })

        assert response.status_code == 200
        data = response.get_json()
        assert data['orderId'] == '445566'
        assert data['status'] == 'PENDING'
        assert data['filledSize'] == '0'

    @patch('main.get_info_client')
    @patch('main.get_exchange_client')
    def test_order_filled_status_defaults_filled_size_to_requested_size(self, mock_get_exchange, mock_get_info, client):
        mock_info = Mock()
        mock_info.all_mids.return_value = {'BTC': '50000.0'}
        mock_get_info.return_value = mock_info

        mock_exchange = Mock()
        mock_exchange.market_order.return_value = {
            'status': 'ok',
            'response': {
                'data': {
                    'statuses': [{
                        'status': 'FILLED',
                        'filled': {
                            'oid': 9999,
                            'px': '50000.0'
                        }
                    }]
                }
            }
        }
        mock_get_exchange.return_value = mock_exchange

        response = client.post('/order', json={
            'username': 'testuser',
            'symbol': 'BTC',
            'side': 'BUY',
            'type': 'MARKET',
            'size': '0.2',
            'hyperliquidKey': 'testkey'
        })

        assert response.status_code == 200
        data = response.get_json()
        assert data['status'] == 'FILLED'
        assert data['filledSize'] == '0.2'

    @patch('main.get_exchange_client')
    def test_cancel_order_success(self, mock_get_exchange, client):
        """Test successful order cancellation"""
        mock_exchange = Mock()
        mock_exchange.cancel.return_value = {'status': 'ok'}
        mock_get_exchange.return_value = mock_exchange

        response = client.post('/cancel/12345', json={
            'username': 'testuser',
            'symbol': 'BTC',
            'hyperliquidKey': 'testkey'
        })

        assert response.status_code == 200
        data = response.get_json()
        assert data['status'] == 'cancelled'
        assert data['orderId'] == '12345'

    def test_cancel_order_missing_key(self, client):
        """Test order cancellation without API key"""
        response = client.post('/cancel/12345', json={
            'username': 'testuser',
            'symbol': 'BTC'
        })
        assert response.status_code == 400
        data = response.get_json()
        assert 'hyperliquidKey' in data['error']

    @patch('main.get_exchange_client')
    def test_cancel_all_success(self, mock_get_exchange, client):
        """Test successful cancel all orders"""
        mock_exchange = Mock()
        mock_exchange.cancel_all_orders.return_value = {'status': 'ok'}
        mock_get_exchange.return_value = mock_exchange

        response = client.post('/cancel-all', json={
            'username': 'testuser',
            'symbol': 'BTC',
            'hyperliquidKey': 'testkey'
        })

        assert response.status_code == 200
        data = response.get_json()
        assert data['status'] == 'success'

    @patch('main.get_info_client')
    def test_get_positions(self, mock_get_info, client):
        """Test getting user positions"""
        mock_info = Mock()
        mock_info.user_state.return_value = {
            'assetPositions': [
                {
                    'position': {
                        'coin': 'BTC',
                        'szi': '0.5',
                        'entryPx': '50000.0',
                        'unrealizedPnl': '500.0',
                        'returnOnEquity': '0.05',
                        'leverage': {'value': '2.0'},
                        'liquidationPx': '45000.0',
                        'marginUsed': '12500.0'
                    }
                }
            ]
        }
        mock_get_info.return_value = mock_info

        response = client.post('/positions', json={
            'user': 'testuser',
            'hyperliquidKey': '0xAddress:testkey'
        })
        assert response.status_code == 200
        data = response.get_json()
        assert len(data) == 1
        assert data[0]['symbol'] == 'BTC'
        assert data[0]['size'] == '0.5'

    @patch('main.get_info_client')
    def test_get_balance(self, mock_get_info, client):
        """Test getting user balance"""
        mock_info = Mock()
        mock_info.user_state.return_value = {
            'marginSummary': {
                'accountValue': '100000.0',
                'totalMarginUsed': '25000.0',
                'totalNtlPos': '50000.0',
                'totalRawUsd': '100000.0',
                'withdrawable': '75000.0'
            }
        }
        mock_get_info.return_value = mock_info

        response = client.post('/balance', json={
            'user': 'testuser',
            'hyperliquidKey': '0xAddress:testkey'
        })
        assert response.status_code == 200
        data = response.get_json()
        assert data['accountValue'] == '100000.0'
        assert data['withdrawable'] == '75000.0'

    def test_get_balance_missing_key(self, client):
        """Test getting balance without API key"""
        response = client.post('/balance', json={'user': 'testuser'})
        assert response.status_code == 400
        data = response.get_json()
        assert 'hyperliquidKey' in data['error']

    @patch('main.get_info_client')
    def test_get_orders(self, mock_get_info, client):
        """Test getting open orders"""
        mock_info = Mock()
        mock_info.open_orders.return_value = [
            {
                'oid': 12345,
                'coin': 'BTC',
                'side': 'B',
                'sz': '0.1',
                'limitPx': '50000.0',
                'timestamp': 1234567890
            }
        ]
        mock_get_info.return_value = mock_info

        response = client.post('/orders', json={
            'user': 'testuser',
            'hyperliquidKey': '0xAddress:testkey'
        })
        assert response.status_code == 200
        data = response.get_json()
        assert len(data) == 1
        assert data[0]['orderId'] == 12345
        assert data[0]['symbol'] == 'BTC'
        assert data[0]['side'] == 'BUY'

    @patch('main.get_exchange_client')
    @patch('main.get_info_client')
    def test_close_position_success(self, mock_get_info, mock_get_exchange, client):
        """Test successful position close"""
        # Mock position info
        mock_info = Mock()
        mock_info.user_state.return_value = {
            'assetPositions': [
                {
                    'position': {
                        'coin': 'BTC',
                        'szi': '0.5'
                    }
                }
            ]
        }
        mock_get_info.return_value = mock_info

        # Mock exchange
        mock_exchange = Mock()
        mock_exchange.market_order.return_value = {'status': 'ok'}
        mock_get_exchange.return_value = mock_exchange

        response = client.post('/close', json={
            'username': 'testuser',
            'symbol': 'BTC',
            'hyperliquidKey': '0xAddress:testkey'
        })

        assert response.status_code == 200
        data = response.get_json()
        assert data['status'] == 'closed'
        assert data['symbol'] == 'BTC'

    @patch('main.get_info_client')
    def test_close_position_not_found(self, mock_get_info, client):
        """Test closing non-existent position"""
        mock_info = Mock()
        mock_info.user_state.return_value = {
            'assetPositions': []
        }
        mock_get_info.return_value = mock_info

        response = client.post('/close', json={
            'username': 'testuser',
            'symbol': 'BTC',
            'hyperliquidKey': '0xAddress:testkey'
        })

        assert response.status_code == 404
        data = response.get_json()
        assert 'No position found' in data['error']


if __name__ == '__main__':
    pytest.main([__file__, '-v'])
